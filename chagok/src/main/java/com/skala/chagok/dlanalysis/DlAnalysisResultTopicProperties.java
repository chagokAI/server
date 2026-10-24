package com.skala.chagok.dlanalysis;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DL 분석 결과 토픽 설정. 토픽 생성과 리스너 구독이 같은 값을 읽는다.
 *
 * <p>요청 토픽과 마찬가지로 복제 계수는 환경마다 달라(로컬 단일 브로커는 1) 설정으로 받는다.
 *
 * @param retention 결과 토픽과 데드레터 토픽의 보존 기간. 소비 위치를 되돌려 결과를 다시 반영할 수 있는 기간이 곧 이 값이다.
 *                  요청 토픽은 지정하지 않는다. 요청을 다시 읽으면 추론을 다시 돌려 결과만 늘어날 뿐, 복구는 결과 재생으로 한다
 * @param publishTimeout 결과 토픽 발행(라이브 입력기) 확인을 기다리는 최대 시간. 요청 토픽 설정과 따로 둔다
 */
@ConfigurationProperties("chagok.kafka.dl-analysis-result")
public record DlAnalysisResultTopicProperties(String topic, int partitions, int replicas, Duration retention,
        Duration publishTimeout) {

    /**
     * 반영하지 못한 결과를 보관하는 토픽. Spring Kafka 데드레터 복구기의 기본 이름(원 토픽 + "-dlt")과 같게 두어,
     * 운영자가 프레임워크 문서의 이름 규칙대로 찾을 수 있게 한다. 원 파티션 번호를 그대로 쓰므로 파티션 수도 같아야 한다.
     */
    public String deadLetterTopic() {
        return topic + "-dlt";
    }
}
