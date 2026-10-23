package com.skala.chagok.dlanalysis;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.skala.chagok.domain.product.entity.Product;
import com.skala.chagok.domain.inventory.service.InventoryPackingService;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * DL 분석 결과를 item_event에 반영한다.
 *
 * <p>같은 센터의 활성 상품을 식별한 뒤 실제 재고에서 포장 박스를 선택한다. 성공·재고·원장은 함께 확정한다.
 * 포장할 수 없으면 측정값과 상품을 WAITING에 보존한다. 상품 기본 매칭과 실측 좌표는 변경하지 않는다.
 *
 * <p>입고·실사와 같은 센터 잠금 뒤 이벤트를 잠근다. 미완료 이벤트만 포장하며 종료된 결과는 다시 차감하지 않는다.
 */
@Slf4j
@Service
public class DlAnalysisResultRecorder {

    private final DlAnalysisItemEventRepository itemEvents;
    private final DlAnalysisProductRepository products;
    private final WarehouseRepository warehouses;
    private final InventoryPackingService packing;

    DlAnalysisResultRecorder(DlAnalysisItemEventRepository itemEvents, DlAnalysisProductRepository products,
            WarehouseRepository warehouses, InventoryPackingService packing) {
        this.itemEvents = itemEvents;
        this.products = products;
        this.warehouses = warehouses;
        this.packing = packing;
    }

    @Transactional
    public DlAnalysisOutcome recordIdentified(String eventId, UUID warehouseId, String barcode) {
        warehouses.lockById(warehouseId).orElseThrow(() ->
                new DlAnalysisException(DlAnalysisFailureReason.INVALID_RESULT, "이벤트 센터가 없다"));
        var event = itemEvents.findForUpdate(eventId).orElseThrow(() ->
                new DlAnalysisException(DlAnalysisFailureReason.ORPHAN_MESSAGE, "item_event 행 없음"));
        if (!warehouseId.equals(event.getWarehouse().getId())) throw new IllegalArgumentException("이벤트 센터 불일치");
        // 라이브 대기는 중복 결과로 재포장하지 않는다. 정지 후에도 늦게 차감되는 경로를 차단한다.
        if (ItemEventStatus.WAITING.name().equals(event.getStatus())
                && eventId.startsWith(DlAnalysisReplayFeeder.LIVE_EVENT_PREFIX)) return DlAnalysisOutcome.skipped();
        if (!ItemEventStatus.PENDING.name().equals(event.getStatus()) && !ItemEventStatus.WAITING.name().equals(event.getStatus()))
            return DlAnalysisOutcome.skipped();
        List<Product> found = products.findByWarehouse_IdAndCodeAndIsActiveTrue(warehouseId, barcode);
        if (found.isEmpty()) {
            return fail(eventId, DlAnalysisFailureReason.PRODUCT_NOT_FOUND, "barcode=" + barcode);
        }
        if (found.size() > 1) {
            return fail(eventId, DlAnalysisFailureReason.PRODUCT_AMBIGUOUS, "barcode=" + barcode + ", count=" + found.size());
        }
        Product product = found.getFirst();
        var allocation = packing.pack(product, eventId);
        if (!allocation.packed()) {
            itemEvents.waitForPacking(eventId, product, DlAnalysisFailureReason.NO_AVAILABLE_BOX.name(),
                    "재고가 있고 규격에 맞는 사용 가능한 박스가 없습니다.");
            return DlAnalysisOutcome.waiting(DlAnalysisFailureReason.NO_AVAILABLE_BOX);
        }
        if (itemEvents.markSucceeded(eventId, product, allocation.box(), allocation.fillRatePct()) != 1)
            throw new IllegalStateException("포장한 이벤트의 성공 상태를 확정하지 못했습니다.");
        return DlAnalysisOutcome.succeeded();
    }

    @Transactional
    public DlAnalysisOutcome recordFailure(String eventId, DlAnalysisFailureReason reason, String detail) {
        return fail(eventId, reason, detail);
    }

    private DlAnalysisOutcome fail(String eventId, DlAnalysisFailureReason reason, String detail) {
        int updated = itemEvents.failWithReason(eventId, reason.name(),
                detail == null ? null : detail.substring(0, Math.min(500, detail.length())));
        if (updated == 0) {
            log.info("DL 분석 실패 기록 생략(이미 처리됨): eventId={}, reason={}", eventId, reason);
            return DlAnalysisOutcome.skipped();
        }
        log.warn("DL 분석 실패: eventId={}, reason={}, detail={}", eventId, reason, detail);
        return DlAnalysisOutcome.failed(reason);
    }
}
