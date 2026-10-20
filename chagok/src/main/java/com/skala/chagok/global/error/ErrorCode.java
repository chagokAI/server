package com.skala.chagok.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 요청에서 수정되었습니다. 최신 내용을 확인하세요."),
    INVALID_STATE(HttpStatus.CONFLICT, "현재 상태에서 처리할 수 없습니다."),
    REFERENCED_SPEC(HttpStatus.CONFLICT, "사용 중인 박스 규격은 삭제할 수 없습니다."),
    BOX_NOT_FIT(HttpStatus.UNPROCESSABLE_CONTENT, "상품이 박스에 들어가지 않습니다."),
    TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도하세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;
}
