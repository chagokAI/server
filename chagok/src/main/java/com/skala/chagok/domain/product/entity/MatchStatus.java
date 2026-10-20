package com.skala.chagok.domain.product.entity;

/**
 * product.match_status 값. 화면 탭과 1:1로 대응한다.
 * 판정은 매칭 서비스가 DB에 기록하며, 이 서버는 읽어서 보여주기만 한다.
 */
public enum MatchStatus {
    /** 매칭 완료 */
    MATCHED,
    /** 검토 필요 */
    REVIEW,
    /** 미매칭: matched_box_spec_id·fill_rate_pct가 null */
    UNMATCHED,
    /** 상품 수정 후 재매칭 대기 */
    PROCESSING
}
