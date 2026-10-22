package com.skala.chagok.dlanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 접수 → 요청 토픽 → (DL Worker) → 결과 토픽 → 결과 컨슈머 → item_event 반영까지 한 번에 확인한다.
 *
 * <p>DB는 로컬 PostgreSQL(compose)을 쓰고, Kafka는 내장 브로커다. DL Worker 자리에는 요청 토픽에서 요청을 꺼내
 * 결과 토픽에 JSON 문자열을 직접 보내는 가짜 Worker를 둔다. Python Worker가 보내는 것과 같은 형태(타입 헤더 없는
 * 평범한 JSON)로 보내야 계약을 시험할 수 있기 때문이다. 테스트가 만든 행은 끝날 때 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(
        brokerProperties = {"auto.create.topics.enable=false", "group.initial.rebalance.delay.ms=0",
                "log.segment.bytes=10485760", "log.index.size.max.bytes=1048576", "offsets.topic.num.partitions=3"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class DlAnalysisFlowIntegrationTest {

    private static final String BARCODE = "8809037045013";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path MEASUREMENT_LOG = createMeasurementLogPath();

    @TempDir
    static Path imageDir;

    @Autowired
    private DlAnalysisAcceptService acceptService;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private DlAnalysisRequestTopicProperties requestTopic;

    @Autowired
    private DlAnalysisResultTopicProperties resultTopic;

    @Value("${spring.kafka.consumer.group-id}")
    private String resultGroup;

    private final UUID warehouseId = UUID.randomUUID();
    private final UUID boxSpecId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final List<String> eventIds = new ArrayList<>();

    private Consumer<String, String> fakeWorkerConsumer;
    private Producer<String, String> fakeWorkerProducer;

    @DynamicPropertySource
    static void measurementLog(DynamicPropertyRegistry registry) {
        registry.add("chagok.dl.measure.measurement-log", MEASUREMENT_LOG::toString);
        registry.add("chagok.dl.image-root", () -> imageDir.toString());
    }

    @BeforeEach
    void waitUntilListenerOwnsAllPartitions() {
        // 리스너가 파티션을 배정받기 전에 보낸 결과는 배정 후에야 처리된다. 첫 테스트가 그 지연만큼 늦어
        // 시간 초과로 실패하지 않도록, 배정이 끝난 뒤에 시작한다
        listeners.getListenerContainers()
                .forEach(container -> ContainerTestUtils.waitForAssignment(container, resultTopic.partitions()));
    }

    @BeforeEach
    void startFakeWorker() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(broker, "fake-dl-worker-" + UUID.randomUUID(), false);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        fakeWorkerConsumer = new DefaultKafkaConsumerFactory<>(
                consumerProps, new StringDeserializer(), new StringDeserializer()).createConsumer();
        fakeWorkerConsumer.subscribe(List.of(requestTopic.topic()));
        fakeWorkerProducer = new KafkaProducer<>(
                KafkaTestUtils.producerProps(broker), new StringSerializer(), new StringSerializer());
    }

    @BeforeEach
    void insertWarehouseWithMatchedProduct() {
        jdbc.update("insert into warehouse (id, name) values (?, ?)", warehouseId, "테스트 센터");
        jdbc.update("insert into box_spec (id, warehouse_id, code, name, is_active, version) values (?, ?, ?, ?, true, 0)",
                boxSpecId, warehouseId, "BX-T1", "테스트 박스");
        jdbc.update("""
                insert into product (id, warehouse_id, code, name, matched_box_spec_id, fill_rate_pct, is_active, version)
                values (?, ?, ?, ?, ?, 72.50, true, 0)
                """, productId, warehouseId, BARCODE, "테스트 상품", boxSpecId);
    }

    @AfterEach
    void stopFakeWorker() {
        fakeWorkerConsumer.close();
        fakeWorkerProducer.close();
    }

    @AfterEach
    void deleteInsertedRows() {
        eventIds.forEach(id -> jdbc.update("delete from item_event where event_id = ?", id));
        jdbc.update("delete from product where id = ?", productId);
        jdbc.update("delete from box_spec where id = ?", boxSpecId);
        jdbc.update("delete from warehouse where id = ?", warehouseId);
    }

    @Test
    void uploadedCameraImagesReachKafkaAndResultCanBeReadFromTheDatabase() throws Exception {
        String path = "/api/v1/warehouses/" + warehouseId + "/captures";
        var request = multipart(path);
        byte[][] originals = new byte[3][];
        for (int cam=0; cam<3; cam++) {
            var bytes = new java.io.ByteArrayOutputStream();
            var image = new java.awt.image.BufferedImage(1920,1080,java.awt.image.BufferedImage.TYPE_INT_RGB);
            image.setRGB(0,0,cam+1);
            javax.imageio.ImageIO.write(image,"png",bytes);
            originals[cam] = bytes.toByteArray();
            request.file(new MockMultipartFile("images","cam"+(cam+1)+".png","image/png",originals[cam]));
        }
        var response = mvc.perform(request).andExpect(status().isAccepted()).andReturn();
        String eventId = JSON.readTree(response.getResponse().getContentAsString()).get("data").get("eventId").asString();
        eventIds.add(eventId);
        JsonNode message = awaitRequest(eventId);
        for (int cam=0; cam<3; cam++) {
            Path saved = Path.of(message.get("imageRefs").get(cam).asString());
            assertThat(saved.startsWith(imageDir)).isTrue();
            assertThat(Files.readAllBytes(saved)).isEqualTo(originals[cam]);
        }
        // 추론 정확도는 별도 검증됐으므로 여기서는 Worker의 계약 응답으로 전송·반영만 확인한다.
        reply(eventId, known(eventId,BARCODE));
        assertThat(awaitFinished(eventId).get("status")).isEqualTo("SUCCEEDED");
        mvc.perform(get(path+"/"+eventId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageCount").value(3))
                .andExpect(jsonPath("$.data.observation.product.id").value(productId.toString()))
                .andExpect(jsonPath("$.data.observation.boxSpec.id").value(boxSpecId.toString()));
    }

    @Test
    void identifiedItemCopiesProductMatchIntoItemEvent() throws Exception {
        String eventId = accept();

        JsonNode request = awaitRequest(eventId);
        assertThat(request.get("warehouseId").asString()).isEqualTo(warehouseId.toString());
        assertThat(request.get("imageRefs")).hasSize(3);
        reply(eventId, known(eventId, BARCODE));

        Map<String, Object> row = awaitFinished(eventId);
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat(row.get("product_id")).isEqualTo(productId);
        assertThat(row.get("box_spec_id")).isEqualTo(boxSpecId);
        assertThat(row.get("fill_rate_pct").toString()).isEqualTo("72.50");
        assertThat(row.get("placement")).isNull();
        assertThat(jdbc.queryForObject("select measurement->>'widthMm' from item_event where event_id=?", String.class, eventId)).isEqualTo("239");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(measurementLine(eventId))
                .contains("\"barcode\":\"" + BARCODE + "\"", "\"sizeType\":\"measured\"", "\"widthCm\":23.9",
                        "\"outcome\":\"SUCCEEDED\"", "\"latencyMs\":150"));
    }

    @Test
    void acceptedRowKeepsGivenOccurredAt() throws Exception {
        Instant occurredAt = Instant.parse("2026-05-03T01:02:03Z");

        String eventId = acceptService.accept(warehouseId, images(), occurredAt);
        eventIds.add(eventId);
        awaitRequest(eventId);
        reply(eventId, unknown(eventId));

        assertThat(jdbc.queryForObject("select occurred_at from item_event where event_id = ?",
                java.sql.Timestamp.class, eventId).toInstant()).isEqualTo(occurredAt);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(measurementLine(eventId))
                .contains("\"outcome\":\"FAILED\"", "\"reason\":\"UNKNOWN_ITEM\""));
    }

    @Test
    void unknownItemEndsAsFailedWithoutProduct() throws Exception {
        String eventId = accept();
        reply(eventId, unknown(eventId));

        Map<String, Object> row = awaitFinished(eventId);

        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("product_id")).isNull();
    }

    @Test
    void barcodeWithoutActiveProductEndsAsFailed() throws Exception {
        String eventId = accept();
        reply(eventId, known(eventId, "0000000000000"));

        Map<String, Object> row = awaitFinished(eventId);

        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("box_spec_id")).isNull();
    }

    @Test
    void workerFailureEndsAsFailedWithItsReason() throws Exception {
        String eventId = accept();
        reply(eventId, failed(eventId, "IMAGE_UNREADABLE"));

        assertThat(awaitFinished(eventId).get("status")).isEqualTo("FAILED");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(measurementLine(eventId))
                .contains("\"reason\":\"IMAGE_UNREADABLE\""));
    }

    @Test
    void unreadableResultEndsAsFailedInsteadOfStayingPending() throws Exception {
        String eventId = accept();
        reply(eventId, "{\"schemaVersion\": 1, \"eventId\": ");

        assertThat(awaitFinished(eventId).get("status")).isEqualTo("FAILED");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(measurementLine(eventId))
                .contains("\"reason\":\"INVALID_RESULT\""));
    }

    @Test
    void duplicateResultDoesNotOverwriteFinishedJob() throws Exception {
        String eventId = accept();
        reply(eventId, known(eventId, BARCODE));
        assertThat(awaitFinished(eventId).get("status")).isEqualTo("SUCCEEDED");

        RecordMetadata duplicate = reply(eventId, failed(eventId, "DL_UNAVAILABLE"));
        awaitConsumed(duplicate);

        assertThat(statusOf(eventId)).isEqualTo("SUCCEEDED");
    }

    @Test
    void orphanResultIsDroppedAndDoesNotBlockLaterResults() throws Exception {
        String orphanId = UUID.randomUUID().toString();
        reply(orphanId, known(orphanId, BARCODE));

        String eventId = accept();
        reply(eventId, known(eventId, BARCODE));

        assertThat(awaitFinished(eventId).get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    void resultArrivingBeforeItsRowIsRetriedInsteadOfDroppedAsOrphan() throws Exception {
        String eventId = UUID.randomUUID().toString();
        eventIds.add(eventId);

        reply(eventId, known(eventId, BARCODE));
        Thread.sleep(1000);
        jdbc.update("insert into item_event (event_id, occurred_at, warehouse_id, status) values (?, now(), ?, 'PENDING')",
                eventId, warehouseId);

        assertThat(awaitFinished(eventId).get("status")).isEqualTo("SUCCEEDED");
    }

    private String accept() throws IOException {
        String eventId = acceptService.accept(warehouseId, images());
        eventIds.add(eventId);
        assertThat(statusOf(eventId)).isEqualTo("PENDING");
        return eventId;
    }

    /** 가짜 Worker가 요청 토픽에서 이 작업의 요청을 꺼낸다. 앞선 테스트의 요청은 키로 걸러 낸다. */
    private JsonNode awaitRequest(String eventId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : fakeWorkerConsumer.poll(Duration.ofMillis(200))) {
                if (eventId.equals(record.key())) {
                    return JSON.readTree(record.value());
                }
            }
        }
        throw new AssertionError("요청 토픽에서 요청을 찾지 못했다: eventId=" + eventId);
    }

    private RecordMetadata reply(String eventId, String json) throws Exception {
        return fakeWorkerProducer.send(new ProducerRecord<>(resultTopic.topic(), eventId, json)).get();
    }

    /** 결과 컨슈머 그룹의 커밋 오프셋이 이 메시지를 지났는지로 처리 완료를 확인한다. 상태가 바뀌지 않는 경우에 쓴다. */
    private void awaitConsumed(RecordMetadata metadata) {
        TopicPartition partition = new TopicPartition(metadata.topic(), metadata.partition());
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                OffsetAndMetadata committed = admin.listConsumerGroupOffsets(resultGroup)
                        .partitionsToOffsetAndMetadata().get().get(partition);
                return committed != null && committed.offset() > metadata.offset();
            });
        }
    }

    private Map<String, Object> awaitFinished(String eventId) {
        await().atMost(Duration.ofSeconds(20)).until(() -> !"PENDING".equals(statusOf(eventId)));
        return jdbc.queryForMap(
                "select status, product_id, box_spec_id, fill_rate_pct, placement from item_event where event_id = ?",
                eventId);
    }

    private String statusOf(String eventId) {
        return jdbc.queryForObject("select status from item_event where event_id = ?", String.class, eventId);
    }

    private String known(String eventId, String barcode) {
        return result(eventId, "\"outcome\":\"MEASURED\",\"dlStatus\":\"known\",\"barcode\":\"" + barcode + "\","
                + "\"size\":{\"type\":\"measured\",\"widthCm\":23.9,\"depthCm\":8.0,\"heightCm\":3.4,\"volumeCm3\":650.1},"
                + "\"failureReason\":null,\"failureMessage\":null");
    }

    private String unknown(String eventId) {
        return result(eventId, "\"outcome\":\"MEASURED\",\"dlStatus\":\"unknown\",\"barcode\":null,"
                + "\"size\":{\"type\":\"estimated\",\"widthCm\":26.2,\"depthCm\":13.2,\"heightCm\":2.9,\"volumeCm3\":997.1},"
                + "\"failureReason\":null,\"failureMessage\":null");
    }

    private String failed(String eventId, String reason) {
        return result(eventId, "\"outcome\":\"FAILED\",\"dlStatus\":null,\"barcode\":null,\"size\":null,"
                + "\"failureReason\":\"" + reason + "\",\"failureMessage\":\"테스트 실패\"");
    }

    private String result(String eventId, String body) {
        return "{\"schemaVersion\":1,\"eventId\":\"" + eventId + "\",\"warehouseId\":\"" + warehouseId + "\","
                + body + ",\"latencyMs\":150,\"measuredAt\":\"2026-10-21T00:00:00Z\"}";
    }

    private static String measurementLine(String eventId) throws IOException {
        if (Files.notExists(MEASUREMENT_LOG)) {
            return "";
        }
        return Files.readAllLines(MEASUREMENT_LOG).stream()
                .filter(line -> line.contains(eventId))
                .findFirst()
                .orElse("");
    }

    private static Path createMeasurementLogPath() {
        try {
            return Files.createTempDirectory("dl-measurement").resolve("measurements.jsonl");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> images() throws IOException {
        List<String> refs = new ArrayList<>();
        for (int cam = 1; cam <= 3; cam++) {
            Path image = imageDir.resolve("cam" + cam + ".jpg");
            if (Files.notExists(image)) {
                Files.write(image, new byte[] {1, 2, 3});
            }
            refs.add(image.toString());
        }
        return refs;
    }
}
