package com.skala.chagok.domain.procurement.service;

import com.skala.chagok.domain.box.dto.BoxSnapshot;
import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.box.service.BoxSizeClassifier;
import com.skala.chagok.domain.procurement.dto.AgentProposalData;
import com.skala.chagok.domain.procurement.entity.*;
import com.skala.chagok.domain.procurement.repository.ProcurementProposalItemRepository;
import com.skala.chagok.domain.supplier.entity.Supplier;
import com.skala.chagok.domain.supplier.repository.SupplierRepository;
import com.skala.chagok.global.error.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 승인된 신규·맞춤 규격을 발주 초안에 연결한다. 맞춤은 발주 승인 전까지 비활성이다. */
@Service @RequiredArgsConstructor
public class AgentProposalItemService {
    private final BoxSpecRepository boxes;
    private final SupplierRepository suppliers;
    private final ProcurementProposalItemRepository items;
    private final BoxSizeClassifier sizes;
    private final com.skala.chagok.domain.product.service.ProductMatchService matchRefresh;

    public void materialize(ProcurementProposal proposal) {
        var summary = proposal.getSummary() == null ? Map.<String, Object>of() : proposal.getSummary();
        Set<String> stored = new HashSet<>();
        boolean addedActive = false;
        items.findByProposalId(proposal.getId()).forEach(item -> stored.add(item.getBoxSpec().getCode()));
        for (var line : AgentProposalData.pendingLines(summary, stored)) {
            String code = (String) line.get("boxCode");
            if (!stored.add(code)) throw invalid("제안서에 중복된 신규 규격이 있습니다.");
            int qty = nonnegative(line, "qty");
            if (qty == 0) continue;
            int[] dims = AgentProposalData.dimensions(line.get("dims"));
            if (code.isBlank() || code.length() > 20 || dims == null) throw invalid("신규 규격 코드·치수를 확인하세요.");
            int price = nonnegative(line, "unitPriceKrw"), safety = nonnegative(line, "safetyQty");
            int usage = nonnegative(line, "expectedUsage"), current = nonnegative(line, "currentQty");
            boolean custom = "CUSTOM_SPEC".equals(line.get("type"));
            var candidates = AgentProposalData.rows(summary.get("newSpecs")).stream()
                    .filter(candidate -> code.equals(candidate.get("code"))).toList();
            if (candidates.size() != 1) throw invalid("신규 규격의 제안 근거를 확인하세요. 규격=" + code);
            var candidate = candidates.getFirst();
            String ref = candidate.get("ref") instanceof String value ? value : "";
            if (!Arrays.equals(dims, AgentProposalData.dimensions(candidate.get("dims")))
                    || !Objects.equals(price, AgentProposalData.integer(candidate.get("price")))
                    || (!custom && (!"CATALOG".equals(candidate.get("type")) || !ref.matches("boxking:\\d+"))))
                throw invalid(custom ? "맞춤 규격의 치수·단가가 제안서와 다릅니다. 규격=" + code
                        : "신규 규격의 공급사·치수·단가가 카탈로그 제안과 다릅니다. 규격=" + code);
            if (custom) validateCustomQuote(candidate, ref, dims, price);
            var supplier = supplier(proposal, custom);
            String description = (custom ? "custom:" : "catalog:") + ref;
            var existing = boxes.findByWarehouseIdAndCode(proposal.getWarehouse().getId(), code);
            BoxSpec box;
            if (existing.isPresent()) {
                box = existing.get();
                boolean pendingCustom = custom && Boolean.TRUE.equals(box.getIsCustom())
                        && Objects.equals(box.getOnHandQty(), 0) && description.equals(box.getDescription()) && current == 0;
                if ((!Boolean.TRUE.equals(box.getIsActive()) && !pendingCustom) || box.getSupplier() == null
                        || custom != Boolean.TRUE.equals(box.getIsCustom())
                        || !supplier.getId().equals(box.getSupplier().getId())
                        || !Objects.equals(box.getInnerWidthMm(), dims[0]) || !Objects.equals(box.getInnerDepthMm(), dims[1])
                        || !Objects.equals(box.getInnerHeightMm(), dims[2]))
                    throw invalid("같은 코드로 다른 규격이 등록돼 있습니다. 규격=" + code);
            } else {
                if (current != 0) throw invalid("미등록 신규 규격의 기존 재고를 확인하세요. 규격=" + code);
                String name = candidate.get("name") instanceof String value && !value.isBlank() ? value : code;
                if (name.length() > 50) throw invalid("신규 규격 이름이 너무 깁니다.");
                box = boxes.saveAndFlush(BoxSpec.builder().warehouse(proposal.getWarehouse()).code(code).name(name)
                        .sizeClass(sizes.classify(dims[0], dims[1], dims[2]))
                        .innerWidthMm(dims[0]).innerDepthMm(dims[1]).innerHeightMm(dims[2])
                        .supplier(supplier).unitPriceKrw(price).isCustom(custom).isActive(!custom)
                        .onHandQty(0).safetyDays(com.skala.chagok.domain.inventory.service.InventoryPolicyProjection.DEFAULT_SAFETY_DAYS)
                        .alertEnabled(true).stockUpdatedAt(Instant.now())
                        .description(description).build());
                if (!custom) addedActive = true;
            }
            items.saveAndFlush(ProcurementProposalItem.builder().proposal(proposal).boxSpec(box)
                    .onHandQty(current).safetyQty(safety).forecastUsageQty(usage).proposedQty(qty)
                    .unitPriceKrw(price).boxSnapshot(BoxSnapshot.from(box)).build());
        }
        if (addedActive) matchRefresh.recalculateMatches(proposal.getWarehouse().getId(), true);
    }

    /** 발주에 포함된 양수 수량의 맞춤 초안만 활성화할 수 있다. 일반 비활성 규격은 자동 복구하지 않는다. */
    public boolean pendingCustom(ProcurementProposal proposal, BoxSpec box) {
        if (proposal == null || !Boolean.TRUE.equals(box.getIsCustom()) || Boolean.TRUE.equals(box.getIsActive())
                || !Objects.equals(box.getOnHandQty(), 0)) return false;
        var summary = proposal.getSummary() == null ? Map.<String, Object>of() : proposal.getSummary();
        if (!AgentProposalData.approved(summary) || AgentProposalData.rows(summary.get("lines")).stream()
                .noneMatch(line -> "CUSTOM_SPEC".equals(line.get("type")) && box.getCode().equals(line.get("boxCode")))) return false;
        return AgentProposalData.rows(summary.get("newSpecs")).stream().anyMatch(candidate -> {
            int[] dims = AgentProposalData.dimensions(candidate.get("dims"));
            return box.getCode().equals(candidate.get("code")) && dims != null
                    && ("CUSTOM".equals(candidate.get("type")) || "CUSTOM_UNFIT".equals(candidate.get("type")))
                    && ("custom:" + candidate.get("ref")).equals(box.getDescription())
                    && Objects.equals(box.getInnerWidthMm(), dims[0]) && Objects.equals(box.getInnerDepthMm(), dims[1])
                    && Objects.equals(box.getInnerHeightMm(), dims[2]);
        });
    }

    private void validateCustomQuote(Map<String, Object> candidate, String ref, int[] dims, int price) {
        var quote = AgentProposalData.object(candidate.get("orderQuote"));
        if (quote.isEmpty()) quote = AgentProposalData.object(candidate.get("quote"));
        if (!("CUSTOM".equals(candidate.get("type")) || "CUSTOM_UNFIT".equals(candidate.get("type")))
                || !ref.startsWith("upackage:") || ref.length() > 193 || price <= 0
                || !Boolean.TRUE.equals(quote.get("ok")) || !"upackage".equals(quote.get("supplier"))
                || !(quote.get("spec") instanceof String spec) || spec.isBlank() || !ref.equals("upackage:" + spec)
                || !Arrays.equals(dims, AgentProposalData.dimensions(quote.get("dims")))
                || !Objects.equals(price, AgentProposalData.integer(quote.get("unitPrice"))))
            throw invalid("맞춤 규격의 공급사·치수·견적 단가를 확인하세요.");
    }

    private Supplier supplier(ProcurementProposal proposal, boolean custom) {
        var matches = suppliers.findByWarehouseId(proposal.getWarehouse().getId()).stream()
                .filter(s -> custom ? "유팩".equals(s.getName()) || "upackage".equalsIgnoreCase(s.getName())
                        : "박스왕".equals(s.getName()) || "boxking".equalsIgnoreCase(s.getName())).toList();
        if (custom && matches.isEmpty())
            return suppliers.saveAndFlush(Supplier.builder().warehouse(proposal.getWarehouse()).name("유팩").build());
        if (matches.size() != 1)
            throw invalid("이 센터에 공급사 ‘" + (custom ? "유팩" : "박스왕") + "’을 하나 등록해 주세요.");
        return matches.getFirst();
    }

    private static int nonnegative(Map<String, Object> line, String key) {
        Integer value = AgentProposalData.integer(line.get(key));
        if (value == null || value < 0) throw invalid("신규 규격의 " + key + " 값이 누락되었거나 올바르지 않습니다.");
        return value;
    }

    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_STATE, message); }
}
