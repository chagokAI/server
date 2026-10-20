package com.skala.chagok.dlanalysis;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DL 분석 요청 토픽 설정. 토픽 생성과 발행이 같은 값을 읽도록 한곳에 둔다.
 *
 * <p>복제 계수는 환경마다 다르다(로컬 단일 브로커는 1). 그래서 코드 상수가 아니라 설정으로 받는다.
 *
 * @param publishTimeout 발행 확인을 기다리는 최대 시간. 프로듀서 자체 대기 상한보다 길게 둔다
 */
@ConfigurationProperties("chagok.kafka.dl-analysis-request")
public record DlAnalysisRequestTopicProperties(String topic, int partitions, int replicas, Duration publishTimeout) {
}
