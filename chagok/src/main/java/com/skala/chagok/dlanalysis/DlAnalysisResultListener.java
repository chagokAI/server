package com.skala.chagok.dlanalysis;

import com.skala.chagok.domain.event.LiveEventIds;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * DL Worker가 결과 토픽에 보낸 측정 결과를 item_event에 반영한다.
 *
 * <p>예전에는 이 자리에서 Spring이 요청 토픽을 소비하고 DL을 HTTP로 직접 불렀다. 그러면 추론하는 동안 컨슈머
 * 스레드가 묶이고, 원본 이미지 3장을 Spring이 읽어 다시 올리는 I/O가 생긴다. 그래서 Worker가 요청을 직접 소비하고
 * 결과만 토픽으로 돌려주게 바꿨다. 박스 선택과 업무 DB 반영은 그대로 Spring이 맡는다.
 *
 * <p>처리 순서는 「작업 확인 → 결과 검증 → 반영」이다. 센터는 메시지의 warehouseId가 아니라 item_event의 값을 쓴다.
 * 여기서 다시 던지는 예외는 재시도할 가치가 있는 것뿐이다(행이 아직 안 보이는 ORPHAN_MESSAGE, DB 장애).
 * 재시도 간격과 소진 후 처리는 {@link DlAnalysisConsumerConfig}가 정한다.
 */
@Slf4j
@Component
@KafkaIntegrationEnabled
class DlAnalysisResultListener {

    private final DlAnalysisItemEventRepository itemEvents;
    private final DlAnalysisResultRecorder recorder;
    private final DlMeasurementLog measurementLog;
    private final org.springframework.context.ApplicationEventPublisher events;

    DlAnalysisResultListener(
            DlAnalysisItemEventRepository itemEvents,
            DlAnalysisResultRecorder recorder,
            DlMeasurementLog measurementLog,
            org.springframework.context.ApplicationEventPublisher events) {
        this.itemEvents = itemEvents;
        this.recorder = recorder;
        this.measurementLog = measurementLog;
        this.events = events;
    }

    @KafkaListener(topics = "${chagok.kafka.dl-analysis-result.topic}")
    @Transactional
    void onResult(DlAnalysisResult result) {
        String eventId = result.eventId();
        if (eventId == null || eventId.isBlank()) {
            log.error("DL 분석 결과 버림(eventId 없음): outcome={}", result.outcome());
            return;
        }
        log.debug("DL 분석 결과 수신: eventId={}, outcome={}", eventId, result.outcome());
        DlAnalysisItemEventRepository.Snapshot snapshot = itemEvents.findSnapshot(eventId)
                .orElseThrow(() -> new DlAnalysisException(DlAnalysisFailureReason.ORPHAN_MESSAGE,
                        "item_event 행 없음: eventId=" + eventId));
        boolean waitingForPacking = ItemEventStatus.WAITING.name().equals(snapshot.getStatus())
                && !eventId.startsWith(LiveEventIds.PREFIX);
        if (!ItemEventStatus.PENDING.name().equals(snapshot.getStatus())
                && !(waitingForPacking && result.contractViolation() == null && result.known())) {
            log.info("DL 분석 결과 생략(이미 처리됨): eventId={}, status={}", eventId, snapshot.getStatus());
            return;
        }
        DlAnalysisOutcome outcome = apply(eventId, snapshot, result);
        if (result.contractViolation() == null && outcome != null && outcome.status() != null)
            itemEvents.recordMeasurement(eventId, measurement(result));
        measurementLog.write(eventId, result, outcome);
        if (outcome != null && outcome.status() != null)
            events.publishEvent(new DlAnalysisSettledEvent(eventId, snapshot.getWarehouseId()));
    }

    private static Map<String, Object> measurement(DlAnalysisResult result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("dlStatus", result.dlStatus());
        value.put("sizeType", result.size() == null ? null : result.size().type());
        value.put("widthMm", result.size() == null ? null : mm(result.size().widthCm()));
        value.put("depthMm", result.size() == null ? null : mm(result.size().depthCm()));
        value.put("heightMm", result.size() == null ? null : mm(result.size().heightCm()));
        value.put("latencyMs", result.latencyMs());
        value.put("measuredAt", result.measuredAt());
        value.put("failureReason", result.failureReason());
        return value;
    }

    private static Integer mm(Double cm) {
        if (cm == null || !Double.isFinite(cm) || cm <= 0 || cm > Integer.MAX_VALUE / 10.0) return null;
        return java.math.BigDecimal.valueOf(cm).movePointRight(1).setScale(0, java.math.RoundingMode.HALF_UP).intValueExact();
    }

    private DlAnalysisOutcome apply(String eventId, DlAnalysisItemEventRepository.Snapshot snapshot, DlAnalysisResult result) {
        String violation = result.contractViolation();
        if (violation != null) {
            return recorder.recordFailure(eventId, DlAnalysisFailureReason.INVALID_RESULT, violation);
        }
        if (!result.measured()) {
            return recorder.recordFailure(eventId, result.failureReasonCode(), result.failureMessage());
        }
        if (result.known()) {
            return recorder.recordIdentified(eventId, snapshot.getWarehouseId(), result.barcode());
        }
        return recorder.recordFailure(eventId, DlAnalysisFailureReason.UNKNOWN_ITEM, "dlStatus=" + result.dlStatus());
    }
}
