package com.skala.chagok.global.config;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 센터 시간대, 화면 GET 권장 갱신 주기, 재고 판정 기준.
 */
@ConfigurationProperties(prefix = "chagok")
public record ChagokProperties(
        @DefaultValue("Asia/Seoul") ZoneId timezone,
        @DefaultValue("300") int refreshAfterSeconds,
        @DefaultValue Inventory inventory
) {

    /**
     * @param cautionBufferDays     예상 소진일이 (공급사 리드타임 + 이 값) 이하이면 '주의'
     * @param targetDaysToStockout  평균 재고 소진일 목표 (요약 카드의 "목표 14일 이상")
     */
    public record Inventory(
            @DefaultValue("3") int cautionBufferDays,
            @DefaultValue("14") int targetDaysToStockout
    ) {
    }
}
