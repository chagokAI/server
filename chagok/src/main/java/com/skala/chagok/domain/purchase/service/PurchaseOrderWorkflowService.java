package com.skala.chagok.domain.purchase.service;

import com.skala.chagok.domain.box.dto.BoxSnapshot;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.inventory.repository.InventoryMovementRepository;
import com.skala.chagok.domain.procurement.repository.*;
import com.skala.chagok.domain.purchase.dto.request.*;
import com.skala.chagok.domain.purchase.dto.response.*;
import com.skala.chagok.domain.purchase.entity.*;
import com.skala.chagok.domain.purchase.repository.*;
import com.skala.chagok.domain.user.entity.AppUser;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import com.skala.chagok.global.auth.CurrentUserProvider;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional
public class PurchaseOrderWorkflowService {
    private final WarehouseRepository warehouses;
    private final ProcurementProposalRepository proposals;
    private final ProcurementProposalItemRepository proposalItems;
    private final PurchaseOrderRepository orders;
    private final PurchaseOrderItemRepository items;
    private final BoxSpecRepository boxes;
    private final InventoryMovementRepository movements;
    private final CurrentUserProvider users;
    private final ChagokProperties properties;
    private final com.skala.chagok.domain.notification.service.DomainNotificationService notices;

    public record DraftResult(boolean created, DraftGroupResponse data) {}

    public DraftResult fromProposal(UUID warehouseId, DraftFromProposalRequest request) {
        var warehouse = warehouses.lockById(warehouseId).orElseThrow(() -> BusinessException.notFound("센터"));
        var proposal = proposals.findByIdAndWarehouseId(request.proposalId(), warehouseId)
                .orElseThrow(() -> BusinessException.notFound("발주 제안서"));
        var existing = orders.findByProposalIdOrderByPoNoAsc(proposal.getId());
        if (!existing.isEmpty()) return new DraftResult(false, group(proposal.getId(), existing));
        checkDate(request.requestedDeliveryDate());
        var user = user(warehouseId);
        var lines = proposalItems.findByProposalId(proposal.getId());
        if (lines.stream().anyMatch(line -> line.getProposedQty() == null || line.getProposedQty() < 0))
            throw new BusinessException(ErrorCode.INVALID_STATE, "제안 수량이 누락되었습니다.");
        var grouped = new LinkedHashMap<UUID, List<com.skala.chagok.domain.procurement.entity.ProcurementProposalItem>>();
        for (var line : lines) {
            if (line.getProposedQty() == 0) continue;
            var box = line.getBoxSpec();
            if (!warehouseId.equals(box.getWarehouse().getId()) || !Boolean.TRUE.equals(box.getIsActive())
                    || box.getSupplier() == null || line.getUnitPriceKrw() == null || line.getUnitPriceKrw() < 0)
                throw new BusinessException(ErrorCode.INVALID_STATE, "활성 규격·공급사·저장 단가를 확인하세요. 규격=" + box.getCode());
            grouped.computeIfAbsent(box.getSupplier().getId(), key -> new ArrayList<>()).add(line);
        }
        if (grouped.isEmpty()) throw new BusinessException(ErrorCode.INVALID_STATE, "발주할 제안 수량이 없습니다.");
        var result = new ArrayList<PurchaseOrder>();
        for (var supplierLines : grouped.values()) {
            var supplier = supplierLines.getFirst().getBoxSpec().getSupplier();
            var date = request.requestedDeliveryDate() != null ? request.requestedDeliveryDate()
                    : LocalDate.now(properties.timezone()).plusDays(supplier.getLeadTimeDays() == null ? 0 : supplier.getLeadTimeDays());
            var order = orders.saveAndFlush(PurchaseOrder.builder().warehouse(warehouse).proposal(proposal).supplier(supplier)
                    .poNo(number()).status(PurchaseOrderStatus.PENDING).requestedBy(user).requestedDeliveryDate(date).build());
            items.saveAllAndFlush(supplierLines.stream().map(line -> PurchaseOrderItem.builder().purchaseOrder(order)
                    .boxSpec(line.getBoxSpec()).qty(line.getProposedQty()).unitPriceKrw(line.getUnitPriceKrw())
                    .boxSnapshot(line.getBoxSnapshot() == null ? BoxSnapshot.from(line.getBoxSpec()) : line.getBoxSnapshot()).build()).toList());
            result.add(order);
        }
        return new DraftResult(true, group(proposal.getId(), result));
    }

    public PurchaseOrderResponse update(UUID warehouseId, UUID orderId, PurchaseOrderUpdateRequest request) {
        warehouses.lockById(warehouseId).orElseThrow(() -> BusinessException.notFound("센터"));
        var order = orders.findByIdAndWarehouseId(orderId, warehouseId).orElseThrow(() -> BusinessException.notFound("발주서"));
        if (!Objects.equals(order.getVersion(), request.expectedVersion())) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        var lines = items.findByPurchaseOrderIdIn(List.of(orderId));
        if (request.items() == null && request.requestedDeliveryDate() == null && request.status() == null)
            throw BusinessException.invalidField("status", "변경할 내용을 입력하세요.");
        if (request.items() != null || request.requestedDeliveryDate() != null) {
            if (order.getStatus() != PurchaseOrderStatus.PENDING)
                throw new BusinessException(ErrorCode.INVALID_STATE, "승인 대기 발주서만 수정할 수 있습니다.");
            checkDate(request.requestedDeliveryDate());
            if (request.requestedDeliveryDate() != null) order.changeDeliveryDate(request.requestedDeliveryDate());
            if (request.items() != null) {
                var byId = lines.stream().collect(Collectors.toMap(line -> line.getBoxSpec().getId(), line -> line));
                var seen = new HashSet<UUID>();
                for (var change : request.items()) {
                    if (!seen.add(change.boxSpecId()) || !byId.containsKey(change.boxSpecId()))
                        throw BusinessException.invalidField("items", "중복되거나 발주서에 없는 규격입니다.");
                    byId.get(change.boxSpecId()).changeQuantity(change.qty());
                }
                if (lines.stream().noneMatch(line -> line.getQty() != null && line.getQty() > 0))
                    throw BusinessException.invalidField("items", "양수 수량 품목이 하나 이상 필요합니다.");
            }
            // 품목만 바뀌어도 발주서 버전이 올라가도록 변경 시점을 기록한다.
            order.touch();
        }
        if (request.status() != null) {
            var next = request.status();
            if (order.getStatus() == PurchaseOrderStatus.PENDING && next == PurchaseOrderStatus.APPROVED) {
                checkDate(order.getRequestedDeliveryDate());
                if (order.getRequestedDeliveryDate() == null || order.getSupplier() == null
                        || lines.stream().noneMatch(line -> line.getQty() != null && line.getQty() > 0))
                    throw new BusinessException(ErrorCode.INVALID_STATE, "납품일·공급사·품목을 확인하세요.");
                for (var line : lines) {
                    var box = boxes.findByIdAndWarehouseId(line.getBoxSpec().getId(), warehouseId)
                            .orElseThrow(() -> BusinessException.notFound("박스 규격"));
                    if (line.getQty() == null || line.getQty() < 0 || line.getUnitPriceKrw() == null || line.getUnitPriceKrw() < 0
                            || !Boolean.TRUE.equals(box.getIsActive()) || box.getSupplier() == null
                            || !box.getSupplier().getId().equals(order.getSupplier().getId()))
                        throw new BusinessException(ErrorCode.INVALID_STATE, "품목·공급사 정보가 변경되었습니다. 발주서를 확인하세요.");
                }
                order.approve(user(warehouseId));
                notices.publish(warehouseId, "PURCHASE", "발주 승인 완료", order.getPoNo() + " 발주서가 승인되었습니다.", "/purchase-orders");
            } else if (order.getStatus() == PurchaseOrderStatus.PENDING && next == PurchaseOrderStatus.REJECTED) {
                if (request.decisionReason() == null || request.decisionReason().isBlank())
                    throw BusinessException.invalidField("decisionReason", "반려 사유가 필요합니다.");
                order.reject(user(warehouseId), request.decisionReason().trim());
            } else if (order.getStatus() == PurchaseOrderStatus.APPROVED && next == PurchaseOrderStatus.IN_TRANSIT) {
                order.ship();
            } else throw new BusinessException(ErrorCode.INVALID_STATE, "허용되지 않는 발주 상태 변경입니다.");
        }
        orders.flush(); items.flush();
        return PurchaseOrderResponse.from(order, lines);
    }

    @Transactional(readOnly = true)
    public List<OpenPurchaseOrderResponse> open(UUID warehouseId, UUID boxSpecId) {
        if (!warehouses.existsById(warehouseId)) throw BusinessException.notFound("센터");
        if (boxSpecId != null && boxes.findByIdAndWarehouseId(boxSpecId, warehouseId).isEmpty())
            throw BusinessException.notFound("박스 규격");
        var pending = orders.findByWarehouseIdAndStatusInOrderByCreatedAtDesc(warehouseId,
                List.of(PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.IN_TRANSIT));
        var lines = pending.isEmpty() ? Map.<UUID, List<PurchaseOrderItem>>of() : items.findByPurchaseOrderIdIn(
                pending.stream().map(PurchaseOrder::getId).toList()).stream().collect(Collectors.groupingBy(line -> line.getPurchaseOrder().getId()));
        var result = new ArrayList<OpenPurchaseOrderResponse>();
        for (var order : pending) {
            var remaining = lines.getOrDefault(order.getId(), List.of()).stream()
                    .filter(line -> line.getQty() != null && (boxSpecId == null || boxSpecId.equals(line.getBoxSpec().getId())))
                    .map(line -> { long received = movements.receivedQty(order.getId(), line.getBoxSpec().getId());
                        return new OpenPurchaseOrderResponse.Item(line.getBoxSnapshot() == null ? BoxSnapshot.from(line.getBoxSpec()) : line.getBoxSnapshot(),
                                line.getQty(), received, line.getQty() - received); })
                    .filter(line -> line.remainingQty() > 0).toList();
            if (!remaining.isEmpty()) result.add(new OpenPurchaseOrderResponse(order.getId(), order.getPoNo(),
                    order.getSupplier() == null ? null : order.getSupplier().getName(), order.getRequestedDeliveryDate(), remaining));
        }
        return result;
    }

    private DraftGroupResponse group(UUID proposalId, List<PurchaseOrder> list) {
        return new DraftGroupResponse(proposalId, list.stream().map(order -> PurchaseOrderResponse.from(order,
                items.findByPurchaseOrderIdIn(List.of(order.getId())))).toList());
    }
    private String number() { return "PO-" + LocalDate.now(properties.timezone()).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
            + "-" + UUID.randomUUID().toString().substring(0, 8); }
    private AppUser user(UUID warehouseId) {
        var user = users.getCurrentUser();
        if (user.getWarehouse() == null || !warehouseId.equals(user.getWarehouse().getId())) throw new BusinessException(ErrorCode.FORBIDDEN);
        return user;
    }
    private void checkDate(LocalDate date) {
        if (date != null && date.isBefore(LocalDate.now(properties.timezone())))
            throw BusinessException.invalidField("requestedDeliveryDate", "납품 희망일은 오늘 이후여야 합니다.");
    }
}
