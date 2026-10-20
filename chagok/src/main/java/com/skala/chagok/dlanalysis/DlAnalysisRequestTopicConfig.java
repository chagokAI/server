package com.skala.chagok.dlanalysis;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * DL 분석 요청 토픽을 기동 시 생성한다.
 *
 * <p>로컬 Kafka는 오타 토픽이 조용히 생기지 않도록 자동 생성을 꺼 두었다. 대신 Spring Boot가 등록하는
 * KafkaAdmin이 NewTopic 빈을 읽어 없는 토픽을 만든다.
 *
 * <p>이미 있는 토픽은 파티션이 설정값보다 적으면 KafkaAdmin이 늘린다(줄이지는 못한다). 늘어나는 순간 jobId 키의
 * 파티션 배정이 바뀌어, 그때 처리 중이던 작업은 변경 전후 메시지의 순서를 보장받지 못한다.
 * 그래서 partitions 설정은 미처리 요청이 없을 때만 올린다.
 */
@Configuration
@EnableConfigurationProperties(DlAnalysisRequestTopicProperties.class)
class DlAnalysisRequestTopicConfig {

    @Bean
    NewTopic dlAnalysisRequestTopic(DlAnalysisRequestTopicProperties properties) {
        return TopicBuilder.name(properties.topic())
                .partitions(properties.partitions())
                .replicas(properties.replicas())
                .build();
    }
}
