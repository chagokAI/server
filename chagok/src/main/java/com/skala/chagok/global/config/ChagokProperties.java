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
        @DefaultValue Inventory inventory,
        @DefaultValue BoxSize boxSize
) {

    /**
     * 박스 크기 분류(S/M/L) 기준. 내경 부피(가로×세로×높이)로 판정한다.
     *
     * @param smallMaxLiters  이 부피(L) 이하이면 S
     * @param mediumMaxLiters 이 부피(L) 이하이면 M, 초과하면 L
     */
    public record BoxSize(
            @DefaultValue("10") int smallMaxLiters,
            @DefaultValue("50") int mediumMaxLiters
    ) {
    }

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
