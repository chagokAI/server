package com.skala.chagok.global.response;

import com.skala.chagok.global.config.ChagokProperties;

import java.time.OffsetDateTime;
import java.time.Instant;

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
        return of(data, null, null);
    }

    /** dataAsOf는 응답 시각이 아니라 저장된 결과의 입력 데이터 기준 시각이다. */
    public <T> ApiResponse<T> of(T data, Instant dataAsOf, Integer pendingEventCount) {
        ApiResponse.Meta meta = new ApiResponse.Meta(
                OffsetDateTime.now(properties.timezone()),
                dataAsOf == null ? null : dataAsOf.atZone(properties.timezone()).toOffsetDateTime(),
                properties.refreshAfterSeconds(),
                pendingEventCount
        );
        return new ApiResponse<>(data, meta);
    }
}
