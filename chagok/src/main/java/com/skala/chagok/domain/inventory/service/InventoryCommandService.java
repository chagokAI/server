package com.skala.chagok.domain.inventory.service;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.inventory.dto.*;
import com.skala.chagok.domain.inventory.entity.InventoryMovement;
import com.skala.chagok.domain.inventory.repository.InventoryMovementRepository;
import com.skala.chagok.domain.purchase.entity.PurchaseOrderStatus;
import com.skala.chagok.domain.purchase.repository.*;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional
public class InventoryCommandService {
    private final WarehouseRepository warehouses;
    private final BoxSpecRepository boxes;
    private final InventoryMovementRepository movements;
    private final PurchaseOrderRepository orders;
    private final PurchaseOrderItemRepository orderItems;
    private final ChagokProperties properties;
    private final com.skala.chagok.domain.notification.service.DomainNotificationService notices;
    private final org.springframework.context.ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public com.skala.chagok.global.response.PageResponse<InventoryMovementResponse> history(UUID warehouseId, UUID boxId, int page, int size) {
        if (boxes.findByIdAndWarehouseId(boxId, warehouseId).isEmpty()) throw BusinessException.notFound("박스 재고");
        var pageable = com.skala.chagok.global.web.PageRequests.of(page, size, "createdAt,desc", Map.of("createdAt", "createdAt"), "createdAt,desc");
        return com.skala.chagok.global.response.PageResponse.of(movements.findByWarehouseIdAndBoxSpecId(warehouseId, boxId, pageable),
                InventoryMovementResponse::from);
    }

    public InventoryMovementResponse receive(UUID warehouseId, InventoryReceiptRequest request) {
        lock(warehouseId);
        var previous = movements.findByWarehouseIdAndRequestId(warehouseId, request.requestId());
        if (previous.isPresent()) {
            var p = previous.get();
            if (!"RECEIPT".equals(p.getType()) || !p.getBoxSpecId().equals(request.boxSpecId())
                    || !Objects.equals(p.getPurchaseOrderId(), request.purchaseOrderId()) || !Objects.equals(p.getDeltaQty(), request.qty().longValue())
                    || !p.getOccurredOn().equals(request.receivedDate()) || !Objects.equals(p.getMemo(), request.memo()))
                throw new BusinessException(ErrorCode.INVALID_STATE, "동일한 requestId의 내용이 다릅니다.");
            return InventoryMovementResponse.from(p);
        }
        if (request.receivedDate().isAfter(LocalDate.now(properties.timezone())))
            throw BusinessException.invalidField("receivedDate", "미래 입고일은 등록할 수 없습니다.");
        BoxSpec box = box(warehouseId, request.boxSpecId(), request.expectedVersion());
        var order = request.purchaseOrderId() == null ? null : orders.findByIdAndWarehouseId(request.purchaseOrderId(), warehouseId)
                .orElseThrow(() -> BusinessException.notFound("발주서"));
        if (order != null) {
            if (order.getStatus() != PurchaseOrderStatus.APPROVED && order.getStatus() != PurchaseOrderStatus.IN_TRANSIT)
                throw new BusinessException(ErrorCode.INVALID_STATE, "승인 완료·배송 중 발주서에만 입고할 수 있습니다.");
            var line = orderItems.findByPurchaseOrderIdIn(List.of(order.getId())).stream()
                    .filter(item -> item.getBoxSpec().getId().equals(box.getId())).findFirst()
                    .orElseThrow(() -> BusinessException.invalidField("boxSpecId", "발주서에 없는 규격입니다."));
            if (line.getQty() == null || movements.receivedQty(order.getId(), box.getId()) + request.qty() > line.getQty())
                throw BusinessException.invalidField("qty", "발주서의 미입고 수량을 초과합니다.");
        }
        if (box.getOnHandQty() == null || box.getOnHandQty() < 0) throw new BusinessException(ErrorCode.INVALID_STATE, "현재 재고를 먼저 실사 조정하세요.");
        int after;
        try { after = Math.addExact(box.getOnHandQty(), request.qty()); }
        catch (ArithmeticException e) { throw BusinessException.invalidField("qty", "재고 수량 범위를 초과했습니다."); }
        var result = save(warehouseId, box, request.requestId(), request.purchaseOrderId(), "RECEIPT",
                after, request.receivedDate(), request.memo());
        if (order != null && orderItems.findByPurchaseOrderIdIn(List.of(order.getId())).stream()
                .allMatch(item -> item.getQty() != null && movements.receivedQty(order.getId(), item.getBoxSpec().getId()) >= item.getQty())) {
            order.receive(Instant.now());
        }
        return result;
    }

    public InventoryMovementResponse adjust(UUID warehouseId, InventoryAdjustmentRequest request) {
        lock(warehouseId);
        var previous = movements.findByWarehouseIdAndRequestId(warehouseId, request.requestId());
        if (previous.isPresent()) {
            var p = previous.get();
            if (!"ADJUSTMENT".equals(p.getType()) || !p.getBoxSpecId().equals(request.boxSpecId())
                    || !p.getAfterQty().equals(request.onHandQty()) || !Objects.equals(p.getMemo(), request.reason().trim()))
                throw new BusinessException(ErrorCode.INVALID_STATE, "동일한 requestId의 내용이 다릅니다.");
            return InventoryMovementResponse.from(p);
        }
        BoxSpec box = box(warehouseId, request.boxSpecId(), request.expectedVersion());
        return save(warehouseId, box, request.requestId(), null, "ADJUSTMENT", request.onHandQty(),
                LocalDate.now(properties.timezone()), request.reason().trim());
    }

    private InventoryMovementResponse save(UUID warehouseId, BoxSpec box, UUID requestId, UUID orderId,
            String type, int after, LocalDate date, String memo) {
        Integer before = box.getOnHandQty();
        var now = Instant.now();
        var movement = movements.saveAndFlush(InventoryMovement.builder().warehouseId(warehouseId).boxSpecId(box.getId())
                .requestId(requestId).purchaseOrderId(orderId).type(type).beforeQty(before).afterQty(after)
                .deltaQty(before == null ? null : (long) after - before).occurredOn(date).memo(memo).createdAt(now).build());
        boolean wasShort = com.skala.chagok.domain.notification.service.DomainNotificationService.shortage(box);
        box.changeStock(after, now);
        notices.inventory(box, wasShort, !Boolean.FALSE.equals(box.getAlertEnabled()));
        boxes.flush();
        events.publishEvent(new StockChangedEvent(warehouseId));
        return InventoryMovementResponse.from(movement);
    }

    private void lock(UUID warehouseId) {
        warehouses.lockById(warehouseId).orElseThrow(() -> BusinessException.notFound("센터"));
    }
    private BoxSpec box(UUID warehouseId, UUID boxId, Integer version) {
        var box = boxes.findByIdAndWarehouseId(boxId, warehouseId).orElseThrow(() -> BusinessException.notFound("박스 재고"));
        if (!Objects.equals(box.getVersion(), version)) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        return box;
    }
}
