package com.skala.chagok.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 박스 매칭 정책. 값은 팀과 정하며 기본은 기존 동작(90도 회전만)이다.
 *
 * @param tilted 비스듬히 넣는 배치 허용 여부와 여유 간격
 */
@ConfigurationProperties(prefix = "chagok.matching")
public record MatchingProperties(@DefaultValue Tilted tilted) {

    /**
     * @param enabled     true면 기본 판정으로 안 들어가는 박스에 비스듬한 배치 판정을 추가로 적용한다
     * @param clearanceMm 비스듬한 배치 판정에서 상품 각 변에 더하는 여유(mm)
     */
    public record Tilted(@DefaultValue("false") boolean enabled, @DefaultValue("0") int clearanceMm) {
    }
}
