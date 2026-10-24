package com.skala.chagok.dlanalysis;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * DL 분석 결과 토픽을 기동 시 생성한다.
 *
 * <p>결과 토픽은 DL Worker가 발행하지만, 로컬 Kafka는 자동 생성이 꺼져 있어 Worker가 먼저 떠도 토픽이 없다.
 * 요청 토픽처럼 Spring이 두 토픽을 모두 만들고, 운영 순서는 Spring을 먼저 띄우는 것으로 정했다.
 * 파티션을 늘릴 때의 주의점은 요청 토픽({@link DlAnalysisRequestTopicConfig})과 같다.
 *
 * <p>보존 기간은 이미 있는 토픽에도 맞춘다(spring.kafka.admin.modify-topic-configs). 끄면 새로 만드는 토픽에만 걸려,
 * 먼저 띄운 환경의 토픽은 브로커 기본값에 남는다. KafkaAdmin은 여기 선언한 설정만 비교하므로 다른 설정은 건드리지 않는다.
 */
@Configuration
@EnableConfigurationProperties(DlAnalysisResultTopicProperties.class)
@KafkaIntegrationEnabled
class DlAnalysisResultTopicConfig {

    @Bean
    NewTopic dlAnalysisResultTopic(DlAnalysisResultTopicProperties properties) {
        return TopicBuilder.name(properties.topic())
                .partitions(properties.partitions())
                .replicas(properties.replicas())
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(properties.retention().toMillis()))
                .build();
    }

    /** 데드레터 복구기는 원 파티션 번호로 보낸다. 파티션이 모자라면 발행이 실패하므로 결과 토픽과 같은 수로 만든다. */
    @Bean
    NewTopic dlAnalysisResultDeadLetterTopic(DlAnalysisResultTopicProperties properties) {
        return TopicBuilder.name(properties.deadLetterTopic())
                .partitions(properties.partitions())
                .replicas(properties.replicas())
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(properties.retention().toMillis()))
                .build();
    }
}
