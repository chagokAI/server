package com.skala.chagok.dlanalysis;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DL 측정 서버(item-dimension-api) 호출 설정.
 *
 * @param baseUrl 서버 주소. 로컬 실행 기준 http://127.0.0.1:8000
 * @param connectTimeout 연결 시간 상한. DL이 꺼져 있으면 이 시간 안에 재시도 대상으로 넘긴다
 * @param readTimeout 응답 시간 상한. CPU 기준 1건 0.3~0.5초(DL README)보다 넉넉히 둔다
 */
@ConfigurationProperties("chagok.dl.measure")
public record DlMeasureClientProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout) {
}
