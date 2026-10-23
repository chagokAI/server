package com.skala.chagok.dlanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private DlAnalysisResultListener resultListener;

    @Autowired
    private com.skala.chagok.domain.product.service.ProductMatchService productMatches;

    @Autowired private com.skala.chagok.domain.job.service.JobCommandService jobCommands;
    @Autowired private com.skala.chagok.domain.job.service.JobExecutionService jobExecution;
    @Autowired private com.skala.chagok.domain.procurement.service.ProcurementQueryService procurement;

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
        jdbc.update("insert into box_spec (id, warehouse_id, code, name, is_active, version, on_hand_qty, inner_width_mm, inner_depth_mm, inner_height_mm) values (?, ?, ?, ?, true, 0, 100, 239, 80, 34)",
                boxSpecId, warehouseId, "BX-T1", "테스트 박스");
        jdbc.update("""
                insert into product (id, warehouse_id, code, name, matched_box_spec_id, fill_rate_pct, is_active, version, width_mm, depth_mm, height_mm)
                values (?, ?, ?, ?, ?, 72.50, true, 0, 239, 80, 34)
                """, productId, warehouseId, BARCODE, "테스트 상품", boxSpecId);
    }

    @AfterEach
    void stopFakeWorker() {
        fakeWorkerConsumer.close();
        fakeWorkerProducer.close();
    }

    @AfterEach
    void deleteInsertedRows() {
        jdbc.update("delete from procurement_proposal_item where proposal_id in (select id from procurement_proposal where warehouse_id=?)", warehouseId);
        jdbc.update("delete from procurement_proposal where warehouse_id=?", warehouseId);
        jdbc.update("delete from cost_forecast where warehouse_id=?", warehouseId);
        jdbc.update("delete from analysis_job where warehouse_id=?", warehouseId);
        eventIds.forEach(id -> jdbc.update("delete from item_event where event_id = ?", id));
        jdbc.update("delete from inventory_movement where warehouse_id = ?", warehouseId);
        jdbc.update("delete from product where id = ?", productId);
        jdbc.update("delete from box_spec where warehouse_id = ?", warehouseId);
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
        assertThat(row.get("fill_rate_pct").toString()).isEqualTo("100.00");
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

    @Test
    void packingConsumesOneBoxAndCreatesOneLedgerEntry() throws Exception {
        String id = accept();
        resultListener.onResult(knownResult(id));

        assertThat(statusOf(id)).isEqualTo("SUCCEEDED");
        assertThat(stock(boxSpecId)).isEqualTo(99);
        var movement = jdbc.queryForMap("select type, delta_qty, before_qty, after_qty from inventory_movement where warehouse_id=?", warehouseId);
        assertThat(movement).containsEntry("type", "CONSUMPTION").containsEntry("delta_qty", -1L)
                .containsEntry("before_qty", 100).containsEntry("after_qty", 99);
        assertThat(jdbc.queryForObject("select version from box_spec where id=?", Integer.class, boxSpecId)).isEqualTo(1);
    }

    @Test
    void choosesBestInStockBoxWithoutChangingProductMaster() throws Exception {
        jdbc.update("update box_spec set on_hand_qty=0 where id=?", boxSpecId);
        UUID available = anotherBox("BX-T2", 244, 82, 35, 2, true);
        anotherBox("BX-T3", 1000, 1000, 1000, 5, true);
        String id = accept();

        resultListener.onResult(knownResult(id));

        assertThat(awaitFinished(id)).containsEntry("status", "SUCCEEDED").containsEntry("box_spec_id", available);
        assertThat(stock(available)).isEqualTo(1);
        assertThat(stock(boxSpecId)).isZero();
        assertThat(jdbc.queryForObject("select matched_box_spec_id from product where id=?", UUID.class, productId)).isEqualTo(boxSpecId);
    }

    @Test
    void excludesInactiveUnknownNegativeAndNonFittingBoxes() throws Exception {
        jdbc.update("update box_spec set on_hand_qty=0 where id=?", boxSpecId);
        anotherBox("BX-T2", 239, 80, 34, null, true);
        anotherBox("BX-T3", 239, 80, 34, -1, true);
        anotherBox("BX-T4", 239, 80, 34, 5, false);
        anotherBox("BX-T5", 10, 10, 10, 100, true);
        UUID available = anotherBox("BX-T6", 244, 82, 35, 2, true);
        String id = accept();

        resultListener.onResult(knownResult(id));

        assertThat(awaitFinished(id)).containsEntry("status", "SUCCEEDED").containsEntry("box_spec_id", available);
        assertThat(stock(available)).isEqualTo(1);
        assertThat(movementCount()).isEqualTo(1);
    }

    @Test
    void unavailableStockWaitsAndSameEventCanCompleteAfterRestock() throws Exception {
        jdbc.update("update box_spec set on_hand_qty=0 where id=?", boxSpecId);
        String id = accept();
        resultListener.onResult(knownResult(id));

        assertThat(statusOf(id)).isEqualTo("WAITING");
        assertThat(jdbc.queryForObject("select failure_reason from item_event where event_id=?", String.class, id)).isEqualTo("NO_AVAILABLE_BOX");
        assertThat(jdbc.queryForObject("select measurement is not null from item_event where event_id=?", Boolean.class, id)).isTrue();
        assertThat(movementCount()).isZero();
        assertThat(stock(boxSpecId)).isZero();

        jdbc.update("update box_spec set on_hand_qty=1 where id=?", boxSpecId);
        resultListener.onResult(knownResult(id));
        resultListener.onResult(knownResult(id));

        assertThat(statusOf(id)).isEqualTo("SUCCEEDED");
        assertThat(stock(boxSpecId)).isZero();
        assertThat(movementCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select failure_reason from item_event where event_id=?", String.class, id)).isNull();
    }

    @Test
    void unknownStockWaitsInsteadOfAssumingAvailability() throws Exception {
        jdbc.update("update box_spec set on_hand_qty=null where id=?", boxSpecId);
        String id = accept();
        resultListener.onResult(knownResult(id));
        assertThat(statusOf(id)).isEqualTo("WAITING");
        assertThat(stock(boxSpecId)).isNull();
        assertThat(movementCount()).isZero();
    }

    @Test
    void manualAssignmentDoesNotSilentlySwitchToAnotherBox() throws Exception {
        jdbc.update("update product set match_source='MANUAL', match_status='MATCHED' where id=?", productId);
        jdbc.update("update box_spec set on_hand_qty=0 where id=?", boxSpecId);
        UUID alternative = anotherBox("BX-T2", 244, 82, 35, 2, true);
        String id = accept();
        resultListener.onResult(knownResult(id));
        assertThat(statusOf(id)).isEqualTo("WAITING");
        assertThat(stock(alternative)).isEqualTo(2);
        assertThat(movementCount()).isZero();
    }

    @Test
    void lowFillRateCandidateIsPackedWhileTheReviewLabelStaysOnTheProduct() throws Exception {
        // 박스 1000mm³ 대비 충전율이 1% 미만이라 검토 라벨 대상이지만, 라벨은 포장 여부를 정하지 않는다.
        jdbc.update("update box_spec set inner_width_mm=1000, inner_depth_mm=1000, inner_height_mm=1000 where id=?", boxSpecId);
        jdbc.update("update product set match_status='REVIEW', match_source='DIMENSIONS' where id=?", productId);
        String id = accept();
        resultListener.onResult(knownResult(id));
        assertThat(awaitFinished(id)).containsEntry("box_spec_id", boxSpecId);
        assertThat(statusOf(id)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select failure_reason from item_event where event_id=?", String.class, id)).isNull();
        assertThat(jdbc.queryForObject("select fill_rate_pct from item_event where event_id=?", java.math.BigDecimal.class, id))
                .isLessThan(java.math.BigDecimal.valueOf(45));
        assertThat(stock(boxSpecId)).isEqualTo(99);
        assertThat(movementCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select match_status from product where id=?", String.class, productId)).isEqualTo("REVIEW");
    }

    @Test
    void concurrentDuplicateResultsConsumeOnlyOneBox() throws Exception {
        String id = accept();
        applyConcurrently(id, id);
        assertThat(statusOf(id)).isEqualTo("SUCCEEDED");
        assertThat(stock(boxSpecId)).isEqualTo(99);
        assertThat(movementCount()).isEqualTo(1);
    }

    @Test
    void differentConcurrentEventsEachConsumeOneBox() throws Exception {
        String first = accept(), second = accept();
        applyConcurrently(first, second);
        assertThat(statusOf(first)).isEqualTo("SUCCEEDED");
        assertThat(statusOf(second)).isEqualTo("SUCCEEDED");
        assertThat(stock(boxSpecId)).isEqualTo(98);
        assertThat(movementCount()).isEqualTo(2);
    }

    @Test
    void lastBoxCannotBeAssignedToTwoConcurrentEvents() throws Exception {
        jdbc.update("update box_spec set on_hand_qty=1 where id=?", boxSpecId);
        String first = accept(), second = accept();
        applyConcurrently(first, second);
        assertThat(List.of(statusOf(first), statusOf(second))).containsExactlyInAnyOrder("SUCCEEDED", "WAITING");
        assertThat(stock(boxSpecId)).isZero();
        assertThat(movementCount()).isEqualTo(1);
    }

    @Test
    void measurementFailureRollsBackEventStockAndLedger() throws Exception {
        String id = accept();
        jdbc.execute("alter table item_event add constraint packing_test_measurement_failure check (event_id <> '" + id + "' or measurement is null)");
        try {
            assertThatThrownBy(() -> resultListener.onResult(knownResult(id)))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(statusOf(id)).isEqualTo("PENDING");
            assertThat(stock(boxSpecId)).isEqualTo(100);
            assertThat(movementCount()).isZero();
        } finally {
            jdbc.execute("alter table item_event drop constraint packing_test_measurement_failure");
        }
        resultListener.onResult(knownResult(id));
        assertThat(statusOf(id)).isEqualTo("SUCCEEDED");
        assertThat(stock(boxSpecId)).isEqualTo(99);
        assertThat(movementCount()).isEqualTo(1);
    }

    @Test
    void masterRematchingKeepsIdealBoxEvenWhenOutOfStockAndDoesNotConsume() {
        jdbc.update("update box_spec set on_hand_qty=0 where id=?", boxSpecId);
        anotherBox("BX-T2", 244, 82, 35, 2, true);
        jdbc.update("update product set matched_box_spec_id=null, match_source=null, match_status='UNMATCHED' where id=?", productId);
        productMatches.recalculateUnmatched(warehouseId);
        assertThat(jdbc.queryForObject("select matched_box_spec_id from product where id=?", UUID.class, productId)).isEqualTo(boxSpecId);
        assertThat(stock(boxSpecId)).isZero();
        assertThat(movementCount()).isZero();
    }

    @Test
    void procurementDemandDoesNotFollowTemporaryAlternativeAllocation() throws Exception {
        jdbc.update("update box_spec set on_hand_qty=0 where id=?", boxSpecId);
        UUID alternative = anotherBox("BX-T2", 244, 82, 35, 2, true);
        String id = accept();
        resultListener.onResult(knownResult(id));
        assertThat(awaitFinished(id)).containsEntry("box_spec_id", alternative);

        var demand = procurementQuantities();

        assertThat(demand.get(boxSpecId)).isPositive();
        assertThat(demand.get(alternative)).isZero();
    }

    @Test
    void waitingStockCountsAsProcurementDemandWithoutPretendingItWasConsumed() throws Exception {
        jdbc.update("update box_spec set on_hand_qty=0 where id=?", boxSpecId);
        String id = accept();
        resultListener.onResult(knownResult(id));
        assertThat(statusOf(id)).isEqualTo("WAITING");

        assertThat(procurementQuantities().get(boxSpecId)).isPositive();

        assertThat(statusOf(id)).isEqualTo("WAITING");
        assertThat(stock(boxSpecId)).isZero();
        assertThat(movementCount()).isZero();
    }

    private Map<UUID, Integer> procurementQuantities() {
        jdbc.update("update box_spec set safety_qty=0, unit_price_krw=100 where warehouse_id=?", warehouseId);
        String month = java.time.YearMonth.now(java.time.ZoneId.of("Asia/Seoul")).plusMonths(1).toString();
        jobCommands.submit(warehouseId, month,
                com.skala.chagok.domain.job.entity.AnalysisJob.Type.PROCUREMENT_AND_COST,
                com.skala.chagok.domain.job.entity.AnalysisJob.Trigger.MANUAL);
        assertThat(jobExecution.runNext()).isTrue();
        var proposal = procurement.latestProposal(warehouseId, month).result();
        assertThat(proposal).isNotNull();
        return proposal.items().stream().collect(java.util.stream.Collectors.toMap(item -> item.boxSpec().id(),
                item -> item.forecastUsageQty()));
    }

    private Integer stock(UUID boxId) {
        return jdbc.queryForObject("select on_hand_qty from box_spec where id=?", Integer.class, boxId);
    }

    private long movementCount() {
        return jdbc.queryForObject("select count(*) from inventory_movement where warehouse_id=?", Long.class, warehouseId);
    }

    private UUID anotherBox(String code, int width, int depth, int height, Integer stock, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into box_spec(id,warehouse_id,code,name,inner_width_mm,inner_depth_mm,inner_height_mm,on_hand_qty,is_active,version) values(?,?,?,?,?,?,?,?,?,0)",
                id, warehouseId, code, code, width, depth, height, stock, active);
        return id;
    }

    private DlAnalysisResult knownResult(String id) {
        return JSON.readValue(known(id, BARCODE), DlAnalysisResult.class);
    }

    private void applyConcurrently(String first, String second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of(first, second).stream().map(id -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("concurrent start timed out");
                resultListener.onResult(knownResult(id));
                return null;
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
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
