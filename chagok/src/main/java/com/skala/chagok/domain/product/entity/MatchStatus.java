package com.skala.chagok.domain.product.entity;

/**
 * product.match_status 값. 화면 탭과 1:1로 대응한다.
 * 박스 선택은 Spring이 맡는다(2026-10-21 팀 결정). ProductMatchingService가 치수 기준 1순위를 자동 적용하고
 * Product.refreshMatch가 충전율로 MATCHED/REVIEW를 정한다. 운영자 직접 지정(MANUAL)은 항상 MATCHED다.
 */
public enum MatchStatus {
    /** 매칭 완료 */
    MATCHED,
    /** 검토 필요 */
    REVIEW,
    /** 미매칭: matched_box_spec_id·fill_rate_pct가 null */
    UNMATCHED
}
