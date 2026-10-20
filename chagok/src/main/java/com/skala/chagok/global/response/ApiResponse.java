package com.skala.chagok.global.response;

import java.time.OffsetDateTime;

public record ApiResponse<T>(T data, Meta meta) {

    public record Meta(
            OffsetDateTime servedAt,
            OffsetDateTime dataAsOf,
            Integer refreshAfterSeconds,
            Integer pendingEventCount
    ) {
    }
}
