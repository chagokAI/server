package com.skala.chagok.global.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

public record ErrorResponse(Error error) {

    /**
     * @param details 화면이 오류 원인을 보여주는 데 필요한 추가 정보(예: 삭제 불가 사유). 없으면 JSON에서 생략한다.
     */
    public record Error(String code, String message, List<FieldError> fieldErrors, String requestId,
                        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> details) {
    }

    public record FieldError(String field, String message) {
    }

    public static ErrorResponse of(ErrorCode code, String message, List<FieldError> fieldErrors, String requestId) {
        return of(code, message, fieldErrors, requestId, null);
    }

    public static ErrorResponse of(ErrorCode code, String message, List<FieldError> fieldErrors, String requestId,
                                   Map<String, Object> details) {
        return new ErrorResponse(new Error(code.name(), message, fieldErrors, requestId, details));
    }
}
