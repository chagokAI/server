package com.skala.chagok.dlanalysis;

import org.apache.kafka.clients.admin.NewTopic;
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
                .build();
    }
}
