package com.skala.chagok.global.config;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 센터 시간대와 화면 GET 권장 갱신 주기.
 */
@ConfigurationProperties(prefix = "chagok")
public record ChagokProperties(
        @DefaultValue("Asia/Seoul") ZoneId timezone,
        @DefaultValue("300") int refreshAfterSeconds
) {
}
