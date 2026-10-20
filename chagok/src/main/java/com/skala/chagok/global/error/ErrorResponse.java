package com.skala.chagok.global.error;

import java.util.List;

public record ErrorResponse(Error error) {

    public record Error(String code, String message, List<FieldError> fieldErrors, String requestId) {
    }

    public record FieldError(String field, String message) {
    }

    public static ErrorResponse of(ErrorCode code, String message, List<FieldError> fieldErrors, String requestId) {
        return new ErrorResponse(new Error(code.name(), message, fieldErrors, requestId));
    }
}
