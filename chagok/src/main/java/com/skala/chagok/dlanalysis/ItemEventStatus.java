package com.skala.chagok.dlanalysis;

/**
 * item_event.status에 쓰는 분석·포장 처리 상태. 컬럼은 10자 문자열이므로 name()을 그대로 저장한다.
 *
 * <p>신규 처리의 SUCCEEDED는 포장 배정과 재고 반영 완료, WAITING은 측정 결과를 보존한 미포장 대기다.
 * 도입 전 SUCCEEDED 이력은 소급해 포장·재고 처리하지 않는다.
 */
public enum ItemEventStatus {
    PENDING,
    WAITING,
    SUCCEEDED,
    FAILED
}
