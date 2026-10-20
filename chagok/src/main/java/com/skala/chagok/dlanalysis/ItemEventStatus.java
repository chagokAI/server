package com.skala.chagok.dlanalysis;

/**
 * item_event.status에 쓰는 분석 작업 상태. 컬럼은 10자 문자열이므로 name()을 그대로 저장한다.
 *
 * <p>SUCCEEDED는 「분석 처리가 끝났다」는 뜻이다. 상품에 매칭된 박스가 없어도 분석은 성공이며,
 * 매칭 여부는 상품의 매칭 상태가 따로 나타낸다.
 */
public enum ItemEventStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
