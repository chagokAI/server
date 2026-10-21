package com.skala.chagok.dlanalysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DL 분석 결과 토픽 설정. 토픽 생성과 리스너 구독이 같은 값을 읽는다.
 *
 * <p>요청 토픽과 마찬가지로 복제 계수는 환경마다 달라(로컬 단일 브로커는 1) 설정으로 받는다.
 */
@ConfigurationProperties("chagok.kafka.dl-analysis-result")
public record DlAnalysisResultTopicProperties(String topic, int partitions, int replicas) {
}
