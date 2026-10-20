package com.skala.chagok.global.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.persistence.EntityNotFoundException;

import java.util.List;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 모든 오류를 명세의 {error: {code, message, fieldErrors, requestId}} 형식으로 바꾼다.
 * 업무 오류는 서비스에서 BusinessException(ErrorCode)을 던지면 된다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(EntityNotFoundException e, HttpServletRequest request) {
        return build(ErrorCode.NOT_FOUND, e.getMessage(), List.of(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        return build(ErrorCode.VALIDATION_ERROR, e.getMessage(), List.of(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e, HttpServletRequest request) {
        return build(ErrorCode.INVALID_STATE, e.getMessage(), List.of(), request);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e, HttpServletRequest request) {
        return build(e.getErrorCode(), e.getMessage(), e.getFieldErrors(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException e,
                                                              HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_ERROR, null, fieldErrors, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleParameterValidation(HandlerMethodValidationException e,
                                                                   HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ErrorResponse.FieldError(
                                result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                .toList();
        return build(ErrorCode.VALIDATION_ERROR, null, fieldErrors, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                            HttpServletRequest request) {
        return build(ErrorCode.VALIDATION_ERROR, null,
                List.of(new ErrorResponse.FieldError(e.getName(), "형식이 올바르지 않습니다.")), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e,
                                                                HttpServletRequest request) {
        return build(ErrorCode.VALIDATION_ERROR, null,
                List.of(new ErrorResponse.FieldError(e.getParameterName(), "필수 값입니다.")), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e,
                                                          HttpServletRequest request) {
        // 형식 오류와 정의되지 않은 필드 입력이 모두 여기로 온다.
        return build(ErrorCode.VALIDATION_ERROR, "요청 본문을 해석할 수 없습니다.", List.of(), request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException e,
                                                              HttpServletRequest request) {
        return build(ErrorCode.VERSION_CONFLICT, null, List.of(), request);
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ErrorResponse> handleUnavailable(DataAccessResourceFailureException e,
                                                           HttpServletRequest request) {
        log.error("DB 연결 실패", e);
        return build(ErrorCode.TEMPORARILY_UNAVAILABLE, null, List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
        // 415, 405, 404(경로 없음) 등 Spring MVC 표준 예외는 원래 상태 코드를 유지한다.
        if (e instanceof org.springframework.web.ErrorResponse standard
                && standard.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = standard.getStatusCode();
            ErrorCode code = status.value() == HttpStatus.NOT_FOUND.value()
                    ? ErrorCode.NOT_FOUND : ErrorCode.VALIDATION_ERROR;
            return build(status, code, null, List.of(), request);
        }
        log.error("처리되지 않은 예외", e);
        return build(ErrorCode.INTERNAL_ERROR, null, List.of(), request);
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message,
                                                List<ErrorResponse.FieldError> fieldErrors,
                                                HttpServletRequest request) {
        return build(code.getStatus(), code, message, fieldErrors, request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatusCode status, ErrorCode code, String message,
                                                List<ErrorResponse.FieldError> fieldErrors,
                                                HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = "req-" + UUID.randomUUID();
        }
        String resolvedMessage = message != null ? message : code.getDefaultMessage();
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(code, resolvedMessage, fieldErrors, requestId));
    }
}
