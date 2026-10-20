package com.skala.chagok.dlanalysis;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * DL 분석 요청 토픽을 소비해 DL 측정 서버를 호출하고 결과를 item_event에 반영한다(B안: Spring이 소비).
 *
 * <p>처리 순서는 「작업 확인 → DL 호출 → 결과 반영」이다. DL을 부르기 전에 item_event부터 확인하므로,
 * 고아 메시지(발행 후 저장 실패)는 DL 호출도 DB 변경도 일으키지 않는다.
 *
 * <p>여기서 다시 던지는 예외는 재시도할 가치가 있는 것뿐이다. 행이 아직 안 보이는 경우(ORPHAN_MESSAGE)와
 * DL 일시 장애(DL_UNAVAILABLE)다. 재시도 간격과 소진 후 처리는 {@link DlAnalysisConsumerConfig}가 정한다.
 */
@Slf4j
@Component
@KafkaIntegrationEnabled
class DlAnalysisRequestListener {

    private final DlAnalysisItemEventRepository itemEvents;
    private final DlMeasureClient measureClient;
    private final DlAnalysisResultRecorder recorder;
    private final DlMeasurementLog measurementLog;

    DlAnalysisRequestListener(
            DlAnalysisItemEventRepository itemEvents,
            DlMeasureClient measureClient,
            DlAnalysisResultRecorder recorder,
            DlMeasurementLog measurementLog) {
        this.itemEvents = itemEvents;
        this.measureClient = measureClient;
        this.recorder = recorder;
        this.measurementLog = measurementLog;
    }

    @KafkaListener(topics = "${chagok.kafka.dl-analysis-request.topic}")
    void onRequest(DlAnalysisRequest request) {
        String eventId = request.eventId();
        log.debug("DL 분석 요청 수신: eventId={}", eventId);
        DlAnalysisItemEventRepository.Snapshot snapshot = itemEvents.findSnapshot(eventId)
                .orElseThrow(() -> new DlAnalysisException(DlAnalysisFailureReason.ORPHAN_MESSAGE,
                        "item_event 행 없음: eventId=" + eventId));
        if (!ItemEventStatus.PENDING.name().equals(snapshot.getStatus())) {
            log.info("DL 분석 요청 생략(이미 처리됨): eventId={}, status={}", eventId, snapshot.getStatus());
            return;
        }

        long startedAt = System.nanoTime();
        DlMeasureResult result;
        try {
            result = measureClient.measure(request.imageRefs());
        } catch (DlAnalysisException e) {
            if (e.reason() == DlAnalysisFailureReason.DL_UNAVAILABLE) {
                throw e;
            }
            DlAnalysisOutcome outcome = recorder.recordFailure(eventId, e.reason(), e.getMessage());
            measurementLog.write(eventId, null, null, outcome);
            return;
        }
        long latencyMillis = (System.nanoTime() - startedAt) / 1_000_000;

        DlAnalysisOutcome outcome = result.known()
                ? recorder.recordIdentified(eventId, snapshot.getWarehouseId(), result.barcode())
                : recorder.recordFailure(eventId, DlAnalysisFailureReason.UNKNOWN_ITEM, "status=" + result.status());
        measurementLog.write(eventId, result, latencyMillis, outcome);
    }
}
