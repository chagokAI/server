package com.skala.chagok.dlanalysis;

/**
 * DL 분석이 FAILED로 끝나거나 메시지를 버린 이유.
 *
 * <p>item_event에는 사유 컬럼이 없어 상태만 FAILED로 남는다. 원인별 건수를 확인할 수 있도록
 * 사유는 eventId와 함께 로그 한 줄로 남기고, 로그 검색어로 이 이름을 쓴다.
 */
public enum DlAnalysisFailureReason {
    /** DL이 처음 보는 물품이라고 답했다. 1단계는 탐지만 하고 미등록 물품 처리는 하지 않는다 */
    UNKNOWN_ITEM,
    /** DL이 식별한 바코드의 활성 상품이 같은 센터에 없다 */
    PRODUCT_NOT_FOUND,
    /** 같은 센터에 같은 바코드의 활성 상품이 둘 이상이라 하나로 정할 수 없다 */
    PRODUCT_AMBIGUOUS,
    /** DL이 요청을 거부했다(이미지 수·해상도·물체 미검출). 다시 보내도 결과가 같으므로 재시도하지 않는다 */
    DL_REJECTED,
    /** DL 호출이 연결 실패·시간 초과·서버 오류로 재시도 후에도 실패했다 */
    DL_UNAVAILABLE,
    /** imageRefs가 가리키는 이미지 파일을 읽지 못했다 */
    IMAGE_UNREADABLE,
    /** 재시도 후에도 item_event 행이 없다. 발행 후 저장이 실패한 고아 메시지로 보고 버린다 */
    ORPHAN_MESSAGE
}
