package com.skala.chagok.global.response;

import com.skala.chagok.global.config.ChagokProperties;

import java.time.OffsetDateTime;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

/**
 * 모든 성공 응답을 {data, meta} Envelope로 감싼다. 컨트롤러는 반드시 이 클래스를 거쳐 응답한다.
 */
@Component
@RequiredArgsConstructor
public class ApiResponses {

    private final ChagokProperties properties;

    public <T> ApiResponse<T> of(T data) {
        // dataAsOf·pendingEventCount는 근거 컬럼이 생기기 전까지 null로 둔다.
        ApiResponse.Meta meta = new ApiResponse.Meta(
                OffsetDateTime.now(properties.timezone()),
                null,
                properties.refreshAfterSeconds(),
                null
        );
        return new ApiResponse<>(data, meta);
    }
}
