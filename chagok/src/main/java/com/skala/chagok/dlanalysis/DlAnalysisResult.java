package com.skala.chagok.dlanalysis;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DL Worker가 결과 토픽에 싣는 메시지(schemaVersion 1). 형식은 docs/dl-kafka-contract.md를 따른다.
 *
 * <p>생성자에서 검증하지 않는다. 검증에 실패해 역직렬화가 깨지면 메시지가 버려지고 작업이 PENDING에 남기 때문이다.
 * 대신 컨슈머가 {@link #contractViolation()}으로 확인해 계약과 다른 결과를 FAILED(INVALID_RESULT)로 기록한다.
 * Worker가 필드를 늘려도 이 쪽이 깨지지 않도록 모르는 필드는 무시한다.
 *
 * @param outcome MEASURED(DL이 판정을 냈다) 또는 FAILED(판정을 내지 못했다)
 * @param dlStatus MEASURED일 때 known 또는 unknown
 * @param failureReason FAILED일 때 Worker가 정한 사유 코드
 * @param latencyMs Worker가 잰 처리 시간(이미지 읽기 + 추론)
 * @param measuredAt Worker가 처리를 마친 시각(ISO 8601). 측정 결과 기록에만 쓰므로 문자열 그대로 둔다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DlAnalysisResult(
        int schemaVersion,
        String eventId,
        String warehouseId,
        String outcome,
        String dlStatus,
        String barcode,
        Size size,
        String failureReason,
        String failureMessage,
        Long latencyMs,
        String measuredAt) {

    public static final int SCHEMA_VERSION = 1;

    /** Worker가 보낼 수 있는 실패 사유. UNKNOWN_ITEM 같은 업무 판정 사유는 Spring만 정한다. */
    private static final Set<DlAnalysisFailureReason> WORKER_REASONS = Set.of(
            DlAnalysisFailureReason.INVALID_REQUEST,
            DlAnalysisFailureReason.IMAGE_UNREADABLE,
            DlAnalysisFailureReason.DL_REJECTED,
            DlAnalysisFailureReason.DL_UNAVAILABLE);

    public boolean measured() {
        return "MEASURED".equals(outcome);
    }

    public boolean known() {
        return measured() && "known".equals(dlStatus);
    }

    /** contractViolation()이 null일 때만 부른다. */
    public DlAnalysisFailureReason failureReasonCode() {
        return DlAnalysisFailureReason.valueOf(failureReason);
    }

    /**
     * 계약과 어긋난 점을 한 줄로 돌려준다. 맞으면 null.
     */
    public String contractViolation() {
        if (schemaVersion != SCHEMA_VERSION) {
            return "지원하지 않는 schemaVersion: " + schemaVersion;
        }
        if ("FAILED".equals(outcome)) {
            return workerReason() == null ? "FAILED인데 failureReason이 계약 코드가 아니다: " + failureReason : null;
        }
        if (!measured()) {
            return "outcome이 MEASURED/FAILED가 아니다: " + outcome;
        }
        if (!"known".equals(dlStatus) && !"unknown".equals(dlStatus)) {
            return "dlStatus가 known/unknown이 아니다: " + dlStatus;
        }
        if (size == null) {
            return "MEASURED인데 size가 없다";
        }
        if (known() && (barcode == null || barcode.isBlank())) {
            return "known인데 barcode가 없다";
        }
        return null;
    }

    private DlAnalysisFailureReason workerReason() {
        for (DlAnalysisFailureReason reason : WORKER_REASONS) {
            if (reason.name().equals(failureReason)) {
                return reason;
            }
        }
        return null;
    }

    /**
     * DL 응답 치수. 단위 cm, w ≥ d ≥ h.
     *
     * @param type measured(등록 상품의 라벨 치수) 또는 estimated(모델 추정)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Size(String type, Double widthCm, Double depthCm, Double heightCm, Double volumeCm3) {
    }
}
