package com.skala.chagok.dlanalysis;

/**
 * DL 분석이 FAILED로 끝나거나 메시지를 버린 이유.
 *
 * <p>item_event에는 사유 컬럼이 없어 상태만 FAILED로 남는다. 원인별 건수를 확인할 수 있도록
 * 사유는 eventId와 함께 로그 한 줄로 남기고, 로그 검색어로 이 이름을 쓴다.
 *
 * <p>INVALID_REQUEST·IMAGE_UNREADABLE·DL_REJECTED·DL_UNAVAILABLE은 DL Worker가 결과 메시지로 보내는 코드다
 * (docs/dl-kafka-contract.md). 나머지는 Spring이 결과를 반영하면서 정한다.
 */
public enum DlAnalysisFailureReason {
    /** DL이 처음 보는 물품이라고 답했다. 1단계는 탐지만 하고 미등록 물품 처리는 하지 않는다 */
    UNKNOWN_ITEM,
    /** DL이 식별한 바코드의 활성 상품이 같은 센터에 없다 */
    PRODUCT_NOT_FOUND,
    /** 같은 센터에 같은 바코드의 활성 상품이 둘 이상이라 하나로 정할 수 없다 */
    PRODUCT_AMBIGUOUS,
    /** 같은 센터에 재고가 있고 규격에 맞는 포장 후보가 없어 대기한다 */
    NO_AVAILABLE_BOX,
    /** Worker가 요청 메시지를 계약과 다르다고 판단했다(필드 누락·이미지 수·schemaVersion) */
    INVALID_REQUEST,
    /** 모델이 입력을 거부했다(해상도·물체 미검출). 다시 보내도 결과가 같으므로 재시도하지 않는다 */
    DL_REJECTED,
    /** Worker의 추론이 예상하지 못한 오류로 재시도 후에도 실패했다 */
    DL_UNAVAILABLE,
    /** imageRefs가 가리키는 이미지 파일을 Worker가 읽지 못했다 */
    IMAGE_UNREADABLE,
    /** 결과 메시지가 계약과 다르다(JSON 오류·필수 필드 누락·모르는 코드) */
    INVALID_RESULT,
    /** 재시도 후에도 item_event 행이 없다. 발행 후 저장이 실패한 고아 메시지로 보고 버린다 */
    ORPHAN_MESSAGE
}
