package com.skala.chagok.dlanalysis;

/**
 * DL 분석 처리 중 실패. 사유에 따라 컨슈머가 재시도할지, 곧바로 FAILED로 기록할지 정한다.
 */
public class DlAnalysisException extends RuntimeException {

    private final DlAnalysisFailureReason reason;

    public DlAnalysisException(DlAnalysisFailureReason reason, String message, Throwable cause) {
        super(reason + ": " + message, cause);
        this.reason = reason;
    }

    public DlAnalysisException(DlAnalysisFailureReason reason, String message) {
        this(reason, message, null);
    }

    public DlAnalysisFailureReason reason() {
        return reason;
    }
}
