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

/** 승인된 카탈로그 신규 규격을 발주 초안에 연결한다. 호출자가 센터 잠금·트랜잭션을 소유한다. */
@Service @RequiredArgsConstructor
public class AgentProposalItemService {
    private final BoxSpecRepository boxes;
    private final SupplierRepository suppliers;
    private final ProcurementProposalItemRepository items;
    private final BoxSizeClassifier sizes;

    public void materialize(ProcurementProposal proposal) {
        var summary = proposal.getSummary() == null ? Map.<String, Object>of() : proposal.getSummary();
        Set<String> stored = new HashSet<>();
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
            var candidates = AgentProposalData.rows(summary.get("newSpecs")).stream()
                    .filter(candidate -> code.equals(candidate.get("code"))).toList();
            if (candidates.size() != 1) throw invalid("신규 규격의 카탈로그 근거를 확인하세요. 규격=" + code);
            var candidate = candidates.getFirst();
            if (!"CATALOG".equals(candidate.get("type")) || !(candidate.get("ref") instanceof String ref)
                    || !ref.matches("boxking:\\d+") || !Arrays.equals(dims, AgentProposalData.dimensions(candidate.get("dims")))
                    || !Objects.equals(price, AgentProposalData.integer(candidate.get("price"))))
                throw invalid("신규 규격의 공급사·치수·단가가 카탈로그 제안과 다릅니다. 규격=" + code);
            var supplier = supplier(proposal, ref);
            var existing = boxes.findByWarehouseIdAndCode(proposal.getWarehouse().getId(), code);
            BoxSpec box;
            if (existing.isPresent()) {
                box = existing.get();
                if (!Boolean.TRUE.equals(box.getIsActive()) || box.getSupplier() == null
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
                        .supplier(supplier).unitPriceKrw(price).isCustom(false).isActive(true)
                        .onHandQty(0).safetyQty(safety).alertEnabled(true).stockUpdatedAt(Instant.now())
                        .description("catalog:" + ref).build());
            }
            items.saveAndFlush(ProcurementProposalItem.builder().proposal(proposal).boxSpec(box)
                    .onHandQty(current).safetyQty(safety).forecastUsageQty(usage).proposedQty(qty)
                    .unitPriceKrw(price).boxSnapshot(BoxSnapshot.from(box)).build());
        }
    }

    private Supplier supplier(ProcurementProposal proposal, String ref) {
        // 현재 카탈로그의 공급사 식별자는 boxking이다. 임의의 첫 번째 공급사로 대체하지 않는다.
        var matches = suppliers.findByWarehouseId(proposal.getWarehouse().getId()).stream()
                .filter(s -> "박스왕".equals(s.getName()) || "boxking".equalsIgnoreCase(s.getName())).toList();
        if (!ref.startsWith("boxking:") || matches.size() != 1)
            throw invalid("이 센터에 카탈로그 공급사 ‘박스왕’을 하나 등록해 주세요.");
        return matches.getFirst();
    }

    private static int nonnegative(Map<String, Object> line, String key) {
        Integer value = AgentProposalData.integer(line.get(key));
        if (value == null || value < 0) throw invalid("신규 규격의 " + key + " 값이 누락되었거나 올바르지 않습니다.");
        return value;
    }

    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_STATE, message); }
}
