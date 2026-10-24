package com.skala.chagok.dlanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.skala.chagok.domain.event.entity.ItemEvent;
import com.skala.chagok.domain.event.entity.ItemEventId;
import com.skala.chagok.domain.inventory.service.InventoryPackingService;
import com.skala.chagok.domain.product.entity.Product;
import com.skala.chagok.domain.warehouse.entity.Warehouse;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;

/**
 * 포장 대기(WAITING) 건에 known 결과가 다시 왔는데 상품을 다시 찾지 못하는 경우를 확인한다.
 *
 * <p>실패 기록은 PENDING만 바꾸므로 대기 건은 그대로 남는다. 예전에는 이때 「이미 처리됨」 로그가 남아, 대기 건이
 * 끝난 작업처럼 읽혔다. 대기를 유지한 사실과 그 이유를 로그에 남겨야 운영자가 상품 마스터를 확인할 수 있다.
 */
@ExtendWith(OutputCaptureExtension.class)
class DlAnalysisResultRecorderTest {

    private static final String EVENT_ID = "evt-waiting";
    private static final String BARCODE = "8809037045013";

    private final UUID warehouseId = UUID.randomUUID();
    private final DlAnalysisItemEventRepository itemEvents = mock(DlAnalysisItemEventRepository.class);
    private final DlAnalysisProductRepository products = mock(DlAnalysisProductRepository.class);
    private final WarehouseRepository warehouses = mock(WarehouseRepository.class);
    private final InventoryPackingService packing = mock(InventoryPackingService.class);
    private final DlAnalysisResultRecorder recorder = new DlAnalysisResultRecorder(itemEvents, products, warehouses, packing);

    @Test
    void waitingEventStaysWaitingWhenItsProductIsGone(CapturedOutput output) {
        givenWaitingEvent();
        when(products.findByWarehouse_IdAndCodeAndIsActiveTrue(warehouseId, BARCODE)).thenReturn(List.of());

        var outcome = recorder.recordIdentified(EVENT_ID, warehouseId, BARCODE);

        assertThat(outcome.status()).isNull();
        verify(itemEvents, never()).failWithReason(anyString(), anyString(), any());
        verify(packing, never()).pack(any(), anyString());
        assertThat(output).contains("대기 유지").contains(DlAnalysisFailureReason.PRODUCT_NOT_FOUND.name())
                .doesNotContain("이미 처리됨");
    }

    @Test
    void waitingEventStaysWaitingWhenItsBarcodeBecomesAmbiguous(CapturedOutput output) {
        givenWaitingEvent();
        when(products.findByWarehouse_IdAndCodeAndIsActiveTrue(warehouseId, BARCODE))
                .thenReturn(List.of(mock(Product.class), mock(Product.class)));

        var outcome = recorder.recordIdentified(EVENT_ID, warehouseId, BARCODE);

        assertThat(outcome.status()).isNull();
        verify(itemEvents, never()).failWithReason(anyString(), anyString(), any());
        assertThat(output).contains("대기 유지").contains(DlAnalysisFailureReason.PRODUCT_AMBIGUOUS.name())
                .doesNotContain("이미 처리됨");
    }

    private void givenWaitingEvent() {
        Warehouse warehouse = Warehouse.builder().id(warehouseId).build();
        when(warehouses.lockById(warehouseId)).thenReturn(Optional.of(warehouse));
        when(itemEvents.findForUpdate(EVENT_ID)).thenReturn(Optional.of(ItemEvent.builder()
                .id(new ItemEventId(EVENT_ID, Instant.now()))
                .warehouse(warehouse)
                .status(ItemEventStatus.WAITING.name())
                .build()));
    }
}
