package com.skala.chagok.global.exception;

import java.time.Instant;

/**
 * 공통 에러 응답.
 *
 * @param success   항상 false
 * @param status    HTTP 상태 코드
 * @param message   에러 메시지
 * @param timestamp 발생 시각
 */
public record ErrorResponse(
        boolean success,
        int status,
        String message,
        Instant timestamp
) {

    public static ErrorResponse of(int status, String message) {
        return new ErrorResponse(false, status, message, Instant.now());
    }
}
