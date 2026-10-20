package com.skala.chagok.dlanalysis;

/**
 * DL 분석 1건이 item_event에 어떻게 반영됐는지. 측정 결과 기록에 함께 남겨 업무 결과와 DL 응답을 잇는다.
 *
 * @param status 반영 후 item_event 상태. 이미 처리된 작업이라 반영을 건너뛰었으면 null
 * @param reason FAILED일 때의 사유. 그 밖에는 null
 */
public record DlAnalysisOutcome(ItemEventStatus status, DlAnalysisFailureReason reason) {

    static DlAnalysisOutcome succeeded() {
        return new DlAnalysisOutcome(ItemEventStatus.SUCCEEDED, null);
    }

    static DlAnalysisOutcome failed(DlAnalysisFailureReason reason) {
        return new DlAnalysisOutcome(ItemEventStatus.FAILED, reason);
    }

    static DlAnalysisOutcome skipped() {
        return new DlAnalysisOutcome(null, null);
    }
}
