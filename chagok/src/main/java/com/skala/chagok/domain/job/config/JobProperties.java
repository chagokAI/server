package com.skala.chagok.domain.job.config;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("chagok.jobs")
public record JobProperties(@DefaultValue("28") @Min(1) @Max(366) int lookbackDays,
                            @DefaultValue("100") @Min(1) int orderUnit,
                            @DefaultValue("PT5M") Duration timeout) {
    public JobProperties {
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(90)) < 0)
            throw new IllegalArgumentException("작업 기한은 실행 트랜잭션 상한보다 긴 90초 이상이어야 합니다.");
    }
}
