package com.skala.chagok.dlanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import tools.jackson.databind.json.JsonMapper;

/**
 * 요청 토픽 생성과 발행 형식을 내장 Kafka로 확인한다. DB 없이 돌도록 Kafka 자동 설정만 불러온다.
 *
 * <p>브로커의 토픽 자동 생성을 로컬 compose와 똑같이 꺼 둔다. 그래야 토픽이 KafkaAdmin의 NewTopic으로
 * 만들어졌는지, 아니면 첫 발행이 우연히 만들었는지 구분된다.
 */
@SpringBootTest(classes = {DlAnalysisRequestTopicConfig.class, DlAnalysisRequestProducer.class})
@ImportAutoConfiguration(KafkaAutoConfiguration.class)
@EmbeddedKafka(
        brokerProperties = "auto.create.topics.enable=false",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class DlAnalysisRequestProducerTest {

    @Autowired
    private DlAnalysisRequestProducer producer;

    @Autowired
    private DlAnalysisRequestTopicProperties properties;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Test
    void createsRequestTopicWithConfiguredPartitions() {
        TopicDescription description = kafkaAdmin.describeTopics(properties.topic()).get(properties.topic());

        assertThat(description.partitions()).hasSize(properties.partitions());
    }

    @Test
    void publishesPlainJsonKeyedByJobId() throws Exception {
        DlAnalysisRequest request = DlAnalysisRequest.of(
                "evt-1", "job-1", "wh-1", "sh-1", "captures/job-1/cam1.jpg", Instant.parse("2026-10-20T06:00:00Z"));

        producer.send(request).get(10, TimeUnit.SECONDS);

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(broker, "dl-analysis-request-test", false);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                consumerProps, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            consumer.subscribe(List.of(properties.topic()));
            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, properties.topic());

            assertThat(record.key()).isEqualTo("job-1");
            assertThat(record.headers().lastHeader("__TypeId__")).isNull();
            JsonMapper mapper = JsonMapper.builder().build();
            assertThat(mapper.readTree(record.value())).isEqualTo(mapper.readTree("""
                    {
                      "schemaVersion": 1,
                      "eventId": "evt-1",
                      "jobId": "job-1",
                      "warehouseId": "wh-1",
                      "shipperId": "sh-1",
                      "imageRef": "captures/job-1/cam1.jpg",
                      "capturedAt": "2026-10-20T06:00:00Z"
                    }
                    """));
        }
    }
}
