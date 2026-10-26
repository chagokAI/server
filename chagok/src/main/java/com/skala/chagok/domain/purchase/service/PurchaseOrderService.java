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

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final ProcurementProposalRepository procurementProposalRepository;
    private final BoxSpecRepository boxSpecRepository;
    private final CurrentUserProvider currentUserProvider;
    private final com.skala.chagok.domain.warehouse.repository.WarehouseRepository warehouses;
    private final com.skala.chagok.global.config.ChagokProperties properties;

    /**
     * 발주 제안서로 박스 주문 발주서를 생성한다.
     * 화면에서 수정한 품목·수량과 납품 희망일을 그대로 발주서에 담아 PENDING 상태로 저장하고,
     * 승인은 PATCH 로 따로 한다.
     */
    @Transactional
    public PurchaseOrderCreateResponse create(UUID warehouseId, PurchaseOrderCreateRequest request) {
        warehouses.lockById(warehouseId).orElseThrow(() -> new EntityNotFoundException("센터가 없습니다."));
        if (request.requestedDeliveryDate().isBefore(LocalDate.now(properties.timezone())))
            throw new IllegalArgumentException("납품 희망일은 오늘 이후여야 합니다.");
        UUID proposalId = request.proposalId();
        ProcurementProposal proposal = procurementProposalRepository.findById(proposalId)
                .filter(p -> p.getWarehouse().getId().equals(warehouseId))
                .orElseThrow(() -> new EntityNotFoundException(
                        "해당 창고의 발주 제안서를 찾을 수 없습니다. warehouseId=" + warehouseId + ", proposalId=" + proposalId));
        if (purchaseOrderRepository.existsByProposalId(proposalId)) {
            throw new IllegalStateException("이미 발주서가 생성된 제안서입니다. proposalId=" + proposalId);
        }

        validateTotals(request);
        Map<UUID, BoxSpec> boxSpecs = loadBoxSpecs(warehouseId, request.items());

        var user = currentUserProvider.getCurrentUser();
        if (user.getWarehouse() == null || !warehouseId.equals(user.getWarehouse().getId()))
            throw new com.skala.chagok.global.error.BusinessException(com.skala.chagok.global.error.ErrorCode.FORBIDDEN);
        PurchaseOrder purchaseOrder = PurchaseOrder.createPending(
                proposal.getWarehouse(),
                generatePoNo(),
                proposal,
                resolveSupplier(boxSpecs.values()),
                user,
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

        long totalQty = request.items().stream()
                .mapToLong(PurchaseOrderCreateRequest.Item::qty)
                .sum();
        if (totalQty != request.totalQty()) {
            throw new IllegalArgumentException(
                    "총 수량이 품목 합계와 일치하지 않습니다. 요청=" + request.totalQty() + ", 계산=" + totalQty);
        }

        long totalAmount;
        try { totalAmount = request.items().stream().mapToLong(PurchaseOrderCreateRequest.Item::amountKrw).reduce(0L, Math::addExact); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("총 금액 범위를 초과했습니다."); }
        if (totalAmount != request.totalAmountKrw()) {
            throw new IllegalArgumentException(
                    "총 금액이 품목 합계와 일치하지 않습니다. 요청=" + request.totalAmountKrw() + ", 계산=" + totalAmount);
        }
    }

    private Map<UUID, BoxSpec> loadBoxSpecs(UUID warehouseId, List<PurchaseOrderCreateRequest.Item> items) {
        Set<UUID> boxSpecIds = new LinkedHashSet<>();
        for (PurchaseOrderCreateRequest.Item item : items) {
            if (!boxSpecIds.add(item.boxSpecId())) {
                throw new IllegalArgumentException("중복된 박스 규격입니다. boxSpecId=" + item.boxSpecId());
            }
        }

        Map<UUID, BoxSpec> boxSpecs = boxSpecRepository.findAllById(boxSpecIds).stream()
                .filter(spec -> spec.getWarehouse() != null && warehouseId.equals(spec.getWarehouse().getId()))
                .collect(Collectors.toMap(BoxSpec::getId, Function.identity()));
        if (boxSpecs.size() != boxSpecIds.size()) {
            Set<UUID> missing = new HashSet<>(boxSpecIds);
            missing.removeAll(boxSpecs.keySet());
            throw new EntityNotFoundException("박스 규격을 찾을 수 없습니다. boxSpecId=" + missing);
        }
        for (var item : items) {
            var box = boxSpecs.get(item.boxSpecId());
            if (!Boolean.TRUE.equals(box.getIsActive()) || box.getUnitPriceKrw() == null || !box.getUnitPriceKrw().equals(item.unitPriceKrw()))
                throw new IllegalArgumentException("활성 규격과 현재 단가를 확인하세요. boxSpecId=" + box.getId());
        }
        if (boxSpecs.values().stream().anyMatch(box -> box.getSupplier() == null)
                || boxSpecs.values().stream().map(box -> box.getSupplier().getId()).distinct().count() != 1)
            throw new IllegalArgumentException("공급사별로 발주서를 작성하세요.");
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

    /** 날짜와 임의 식별자로 센터 간에도 겹치지 않도록 발주번호를 만든다. */
    private String generatePoNo() {
        return "PO-" + LocalDate.now(properties.timezone()).format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + UUID.randomUUID().toString().substring(0, 8);
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
