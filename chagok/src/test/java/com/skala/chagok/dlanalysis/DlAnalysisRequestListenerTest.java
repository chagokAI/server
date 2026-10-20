package com.skala.chagok.dlanalysis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * 리스너가 DL 응답과 작업 상태에 따라 어느 기록으로 보내는지, 어떤 실패만 재시도 대상으로 올리는지 확인한다.
 */
class DlAnalysisRequestListenerTest {

    private static final UUID WAREHOUSE_ID = UUID.fromString("6f1c2d3e-4a5b-4c6d-8e7f-90a1b2c3d4e5");
    private static final DlAnalysisRequest REQUEST = DlAnalysisRequest.of(
            "evt-1", WAREHOUSE_ID.toString(), List.of("cam1.jpg", "cam2.jpg", "cam3.jpg"));

    private final DlAnalysisItemEventRepository itemEvents = mock(DlAnalysisItemEventRepository.class);
    private final DlMeasureClient measureClient = mock(DlMeasureClient.class);
    private final DlAnalysisResultRecorder recorder = mock(DlAnalysisResultRecorder.class);
    private final DlAnalysisRequestListener listener = new DlAnalysisRequestListener(itemEvents, measureClient, recorder);

    @Test
    void recordsIdentifiedProductWhenDlKnowsTheItem() {
        givenStatus(ItemEventStatus.PENDING);
        when(measureClient.measure(REQUEST.imageRefs())).thenReturn(new DlMeasureResult("known", "8809037045013"));

        listener.onRequest(REQUEST);

        verify(recorder).recordIdentified("evt-1", WAREHOUSE_ID, "8809037045013");
    }

    @Test
    void recordsUnknownItemAsFailure() {
        givenStatus(ItemEventStatus.PENDING);
        when(measureClient.measure(REQUEST.imageRefs())).thenReturn(new DlMeasureResult("unknown", null));

        listener.onRequest(REQUEST);

        verify(recorder).recordFailure(eq("evt-1"), eq(DlAnalysisFailureReason.UNKNOWN_ITEM), anyString());
        verify(recorder, never()).recordIdentified(anyString(), eq(WAREHOUSE_ID), anyString());
    }

    @Test
    void recordsRejectedRequestWithoutRetry() {
        givenStatus(ItemEventStatus.PENDING);
        when(measureClient.measure(REQUEST.imageRefs()))
                .thenThrow(new DlAnalysisException(DlAnalysisFailureReason.DL_REJECTED, "HTTP 400"));

        listener.onRequest(REQUEST);

        verify(recorder).recordFailure(eq("evt-1"), eq(DlAnalysisFailureReason.DL_REJECTED), anyString());
    }

    @Test
    void recordsUnreadableImageWithoutRetry() {
        givenStatus(ItemEventStatus.PENDING);
        when(measureClient.measure(REQUEST.imageRefs()))
                .thenThrow(new DlAnalysisException(DlAnalysisFailureReason.IMAGE_UNREADABLE, "cam1.jpg"));

        listener.onRequest(REQUEST);

        verify(recorder).recordFailure(eq("evt-1"), eq(DlAnalysisFailureReason.IMAGE_UNREADABLE), anyString());
    }

    @Test
    void rethrowsDlOutageSoTheErrorHandlerRetries() {
        givenStatus(ItemEventStatus.PENDING);
        when(measureClient.measure(REQUEST.imageRefs()))
                .thenThrow(new DlAnalysisException(DlAnalysisFailureReason.DL_UNAVAILABLE, "connection refused"));

        assertThatThrownBy(() -> listener.onRequest(REQUEST))
                .isInstanceOf(DlAnalysisException.class)
                .hasMessageContaining("DL_UNAVAILABLE");
        verifyNoInteractions(recorder);
    }

    @Test
    void skipsRedeliveredMessageOfFinishedJobWithoutCallingDl() {
        givenStatus(ItemEventStatus.SUCCEEDED);

        listener.onRequest(REQUEST);

        verify(measureClient, never()).measure(anyList());
        verifyNoInteractions(recorder);
    }

    @Test
    void treatsMissingRowAsRetryableOrphanWithoutCallingDl() {
        when(itemEvents.findSnapshot("evt-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> listener.onRequest(REQUEST))
                .isInstanceOf(DlAnalysisException.class)
                .hasMessageContaining("ORPHAN_MESSAGE");
        verify(measureClient, never()).measure(anyList());
        verifyNoInteractions(recorder);
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
