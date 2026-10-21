package com.skala.chagok.global.error;

import java.util.List;
import java.util.Map;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<ErrorResponse.FieldError> fieldErrors;
    private final Map<String, Object> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    public BusinessException(ErrorCode errorCode, String message, List<ErrorResponse.FieldError> fieldErrors) {
        this(errorCode, message, fieldErrors, null);
    }

    private BusinessException(ErrorCode errorCode, String message, List<ErrorResponse.FieldError> fieldErrors,
                              Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.fieldErrors = fieldErrors;
        this.details = details;
    }

    /** 응답 error.details에 화면용 추가 정보를 담는다. */
    public static BusinessException withDetails(ErrorCode errorCode, String message, Map<String, Object> details) {
        return new BusinessException(errorCode, message, List.of(), details);
    }

    public static BusinessException notFound(String target) {
        return new BusinessException(ErrorCode.NOT_FOUND, target + "을(를) 찾을 수 없습니다.");
    }

    public static BusinessException invalidField(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getDefaultMessage(),
                List.of(new ErrorResponse.FieldError(field, message)));
    }
}
