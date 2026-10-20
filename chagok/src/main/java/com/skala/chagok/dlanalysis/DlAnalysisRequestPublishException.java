package com.skala.chagok.dlanalysis;

/**
 * DL 분석 요청을 Kafka에 기록했다는 확인을 받지 못했다.
 *
 * <p>발행 확인 후에 작업을 저장하는 순서이므로, 이 예외가 나면 아무것도 저장되지 않은 상태다.
 * 요청 API는 이것을 일시 장애(503 TEMPORARILY_UNAVAILABLE)로 응답한다.
 */
public class DlAnalysisRequestPublishException extends RuntimeException {

    public DlAnalysisRequestPublishException(String jobId, Throwable cause) {
        super("DL 분석 요청 발행 확인 실패: jobId=" + jobId, cause);
    }
}
