package com.skala.chagok.dlanalysis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * 결과 리스너가 Worker의 결과와 작업 상태에 따라 어느 기록으로 보내는지, 어떤 경우만 재시도 대상으로 올리는지 확인한다.
 */
class DlAnalysisResultListenerTest {

    private static final UUID WAREHOUSE_ID = UUID.fromString("6f1c2d3e-4a5b-4c6d-8e7f-90a1b2c3d4e5");
    private static final DlAnalysisResult.Size MEASURED_SIZE = new DlAnalysisResult.Size("measured", 23.9, 8.0, 3.4, 650.1);

    private final DlAnalysisItemEventRepository itemEvents = mock(DlAnalysisItemEventRepository.class);
    private final DlAnalysisResultRecorder recorder = mock(DlAnalysisResultRecorder.class);
    private final DlMeasurementLog measurementLog = mock(DlMeasurementLog.class);
    private final DlAnalysisResultListener listener = new DlAnalysisResultListener(itemEvents, recorder, measurementLog,
            mock(org.springframework.context.ApplicationEventPublisher.class));

    @Test
    void recordsIdentifiedProductForKnownItemUsingTheRowsWarehouse() {
        givenStatus(ItemEventStatus.PENDING);
        // 메시지의 센터가 달라도 item_event의 센터로 상품을 찾는다
        DlAnalysisResult result = measured("known", "8809037045013", "other-warehouse");
        when(recorder.recordIdentified("evt-1", WAREHOUSE_ID, "8809037045013")).thenReturn(DlAnalysisOutcome.succeeded());

        listener.onResult(result);

        verify(recorder).recordIdentified("evt-1", WAREHOUSE_ID, "8809037045013");
        verify(measurementLog).write(eq("evt-1"), eq(result), any());
        var measurement = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(itemEvents).recordMeasurement(eq("evt-1"), measurement.capture());
        org.assertj.core.api.Assertions.assertThat(measurement.getValue()).containsEntry("widthMm", 239)
                .containsEntry("depthMm", 80).containsEntry("heightMm", 34).containsEntry("sizeType", "measured");
    }

    @Test
    void recordsUnknownItemAsFailure() {
        givenStatus(ItemEventStatus.PENDING);

        listener.onResult(measured("unknown", null, WAREHOUSE_ID.toString()));

        verify(recorder).recordFailure(eq("evt-1"), eq(DlAnalysisFailureReason.UNKNOWN_ITEM), anyString());
        verify(recorder, never()).recordIdentified(anyString(), any(), anyString());
    }

    @Test
    void recordsWorkerFailureWithItsReason() {
        givenStatus(ItemEventStatus.PENDING);

        listener.onResult(failed("IMAGE_UNREADABLE"));

        verify(recorder).recordFailure("evt-1", DlAnalysisFailureReason.IMAGE_UNREADABLE, "cam2 이미지를 읽을 수 없다");
    }

    @Test
    void recordsResultOutsideTheContractAsInvalid() {
        givenStatus(ItemEventStatus.PENDING);

        listener.onResult(measured("known", null, WAREHOUSE_ID.toString()));

        verify(recorder).recordFailure(eq("evt-1"), eq(DlAnalysisFailureReason.INVALID_RESULT), anyString());
    }

    @Test
    void rejectsFailureCodesThatOnlySpringMayDecide() {
        givenStatus(ItemEventStatus.PENDING);

        listener.onResult(failed("UNKNOWN_ITEM"));

        verify(recorder).recordFailure(eq("evt-1"), eq(DlAnalysisFailureReason.INVALID_RESULT), anyString());
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        givenStatus(ItemEventStatus.PENDING);
        DlAnalysisResult result = new DlAnalysisResult(2, "evt-1", WAREHOUSE_ID.toString(), "MEASURED", "known",
                "8809037045013", MEASURED_SIZE, null, null, 150L, "2026-10-21T00:00:00Z");

        listener.onResult(result);

        verify(recorder).recordFailure(eq("evt-1"), eq(DlAnalysisFailureReason.INVALID_RESULT), anyString());
    }

    @Test
    void skipsRedeliveredResultOfFinishedJob() {
        givenStatus(ItemEventStatus.SUCCEEDED);

        listener.onResult(measured("known", "8809037045013", WAREHOUSE_ID.toString()));

        verifyNoInteractions(recorder, measurementLog);
    }

    @Test
    void treatsMissingRowAsRetryableOrphan() {
        when(itemEvents.findSnapshot("evt-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> listener.onResult(measured("known", "8809037045013", WAREHOUSE_ID.toString())))
                .isInstanceOf(DlAnalysisException.class)
                .hasMessageContaining("ORPHAN_MESSAGE");
        verifyNoInteractions(recorder, measurementLog);
    }

    @Test
    void dropsResultWithoutEventId() {
        listener.onResult(new DlAnalysisResult(1, null, null, "FAILED", null, null, null, "DL_REJECTED", null, null, null));

        verifyNoInteractions(itemEvents, recorder, measurementLog);
    }

    private static DlAnalysisResult measured(String dlStatus, String barcode, String warehouseId) {
        return new DlAnalysisResult(1, "evt-1", warehouseId, "MEASURED", dlStatus, barcode, MEASURED_SIZE,
                null, null, 150L, "2026-10-21T00:00:00Z");
    }

    private static DlAnalysisResult failed(String reason) {
        return new DlAnalysisResult(1, "evt-1", WAREHOUSE_ID.toString(), "FAILED", null, null, null,
                reason, "cam2 이미지를 읽을 수 없다", 3L, "2026-10-21T00:00:00Z");
    }

    private void givenStatus(ItemEventStatus status) {
        when(itemEvents.findSnapshot("evt-1")).thenReturn(Optional.of(new DlAnalysisItemEventRepository.Snapshot() {
            @Override
            public String getStatus() {
                return status.name();
            }

            @Override
            public UUID getWarehouseId() {
                return WAREHOUSE_ID;
            }
        }));
    }
}
