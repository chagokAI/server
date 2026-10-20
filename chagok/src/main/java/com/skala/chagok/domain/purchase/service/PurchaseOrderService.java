package com.skala.chagok.domain.purchase.service;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.dto.BoxSnapshot;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.procurement.entity.ProcurementProposal;
import com.skala.chagok.domain.procurement.repository.ProcurementProposalRepository;
import com.skala.chagok.domain.purchase.dto.request.PurchaseOrderCreateRequest;
import com.skala.chagok.domain.purchase.dto.response.PurchaseOrderCreateResponse;
import com.skala.chagok.domain.purchase.entity.PurchaseOrder;
import com.skala.chagok.domain.purchase.entity.PurchaseOrderItem;
import com.skala.chagok.domain.purchase.entity.PurchaseOrderItemId;
import com.skala.chagok.domain.purchase.repository.PurchaseOrderItemRepository;
import com.skala.chagok.domain.purchase.repository.PurchaseOrderRepository;
import com.skala.chagok.domain.supplier.entity.Supplier;
import com.skala.chagok.global.auth.CurrentUserProvider;

import jakarta.persistence.EntityNotFoundException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

    private static final DateTimeFormatter PO_NO_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MMdd");

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final ProcurementProposalRepository procurementProposalRepository;
    private final BoxSpecRepository boxSpecRepository;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 발주 제안서를 승인해 박스 주문 발주서를 생성한다.
     * 화면에서 수정한 품목·수량과 납품 희망일을 그대로 발주서에 담아 APPROVED 상태로 저장한다.
     */
    @Transactional
    public PurchaseOrderCreateResponse create(UUID warehouseId, PurchaseOrderCreateRequest request) {
        UUID proposalId = request.proposalId();
        ProcurementProposal proposal = procurementProposalRepository.findById(proposalId)
                .filter(p -> p.getWarehouse().getId().equals(warehouseId))
                .orElseThrow(() -> new EntityNotFoundException(
                        "해당 창고의 발주 제안서를 찾을 수 없습니다. warehouseId=" + warehouseId + ", proposalId=" + proposalId));
        if (purchaseOrderRepository.existsByProposalId(proposalId)) {
            throw new IllegalStateException("이미 발주서가 생성된 제안서입니다. proposalId=" + proposalId);
        }

        validateTotals(request);
        Map<UUID, BoxSpec> boxSpecs = loadBoxSpecs(request.items());

        PurchaseOrder purchaseOrder = PurchaseOrder.createApproved(
                proposal.getWarehouse(),
                generatePoNo(),
                proposal,
                resolveSupplier(boxSpecs.values()),
                currentUserProvider.getCurrentUser(),
                request.requestedDeliveryDate());
        purchaseOrderRepository.save(purchaseOrder);

        saveItems(purchaseOrder, request.items(), boxSpecs);

        return PurchaseOrderCreateResponse.from(purchaseOrder);
    }

    private void validateTotals(PurchaseOrderCreateRequest request) {
        for (PurchaseOrderCreateRequest.Item item : request.items()) {
            long expectedAmount = (long) item.qty() * item.unitPriceKrw();
            if (expectedAmount != item.amountKrw()) {
                throw new IllegalArgumentException(
                        "품목 금액이 수량 × 단가와 일치하지 않습니다. boxSpecId=" + item.boxSpecId()
                                + ", 요청=" + item.amountKrw() + ", 계산=" + expectedAmount);
            }
        }

        int totalQty = request.items().stream()
                .mapToInt(PurchaseOrderCreateRequest.Item::qty)
                .sum();
        if (totalQty != request.totalQty()) {
            throw new IllegalArgumentException(
                    "총 수량이 품목 합계와 일치하지 않습니다. 요청=" + request.totalQty() + ", 계산=" + totalQty);
        }

        long totalAmount = request.items().stream()
                .mapToLong(PurchaseOrderCreateRequest.Item::amountKrw)
                .sum();
        if (totalAmount != request.totalAmountKrw()) {
            throw new IllegalArgumentException(
                    "총 금액이 품목 합계와 일치하지 않습니다. 요청=" + request.totalAmountKrw() + ", 계산=" + totalAmount);
        }
    }

    private Map<UUID, BoxSpec> loadBoxSpecs(List<PurchaseOrderCreateRequest.Item> items) {
        Set<UUID> boxSpecIds = new LinkedHashSet<>();
        for (PurchaseOrderCreateRequest.Item item : items) {
            if (!boxSpecIds.add(item.boxSpecId())) {
                throw new IllegalArgumentException("중복된 박스 규격입니다. boxSpecId=" + item.boxSpecId());
            }
        }

        Map<UUID, BoxSpec> boxSpecs = boxSpecRepository.findAllById(boxSpecIds).stream()
                .collect(Collectors.toMap(BoxSpec::getId, Function.identity()));
        if (boxSpecs.size() != boxSpecIds.size()) {
            Set<UUID> missing = new HashSet<>(boxSpecIds);
            missing.removeAll(boxSpecs.keySet());
            throw new EntityNotFoundException("박스 규격을 찾을 수 없습니다. boxSpecId=" + missing);
        }
        return boxSpecs;
    }

    /** 품목이 모두 같은 공급사면 그 공급사를, 섞여 있으면 특정하지 않는다. */
    private Supplier resolveSupplier(Collection<BoxSpec> boxSpecs) {
        List<Supplier> suppliers = boxSpecs.stream()
                .map(BoxSpec::getSupplier)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return suppliers.size() == 1 ? suppliers.getFirst() : null;
    }

    /** PO-2026-1101 형태로 발주번호를 만든다. 같은 날 두 번째부터는 뒤에 일련번호를 붙인다. */
    private String generatePoNo() {
        String prefix = "PO-" + LocalDate.now().format(PO_NO_DATE_FORMAT);
        long sameDayCount = purchaseOrderRepository.countByPoNoStartingWith(prefix);
        return sameDayCount == 0 ? prefix : prefix + "-" + (sameDayCount + 1);
    }

    private void saveItems(PurchaseOrder purchaseOrder,
                           List<PurchaseOrderCreateRequest.Item> items,
                           Map<UUID, BoxSpec> boxSpecs) {
        List<PurchaseOrderItem> orderItems = items.stream()
                .map(item -> PurchaseOrderItem.builder()
                        .id(new PurchaseOrderItemId(purchaseOrder.getId(), item.boxSpecId()))
                        .purchaseOrder(purchaseOrder)
                        .boxSpec(boxSpecs.get(item.boxSpecId()))
                        .qty(item.qty())
                        .unitPriceKrw(item.unitPriceKrw())
                        .boxSnapshot(BoxSnapshot.from(boxSpecs.get(item.boxSpecId())))
                        .build())
                .toList();
        purchaseOrderItemRepository.saveAll(orderItems);
    }
}
