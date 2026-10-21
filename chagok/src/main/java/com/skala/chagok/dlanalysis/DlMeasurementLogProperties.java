package com.skala.chagok.dlanalysis;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 측정 결과 기록 설정. 일괄 측정 스크립트가 이미 이 키를 쓰고 있어 접두사는 예전 DL 호출 설정과 같은
 * chagok.dl.measure를 유지한다.
 *
 * @param measurementLog 측정 결과 JSON Lines 파일 경로. 비우면 기록하지 않는다
 */
@ConfigurationProperties("chagok.dl.measure")
public record DlMeasurementLogProperties(Path measurementLog) {
}
