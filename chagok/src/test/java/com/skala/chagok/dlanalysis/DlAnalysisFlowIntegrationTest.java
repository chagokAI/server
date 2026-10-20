package com.skala.chagok.dlanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpServer;

/**
 * 접수 → Kafka → 컨슈머 → DL 호출 → item_event 반영까지 한 번에 확인한다.
 *
 * <p>DB는 로컬 PostgreSQL(compose)을 쓰고, Kafka는 내장 브로커, DL은 응답을 바꿔 끼울 수 있는 가짜 HTTP 서버다.
 * 실제 DL 모델 대신 가짜 서버를 쓰는 이유는 known/unknown/미등록 바코드 응답을 마음대로 만들기 위해서다.
 * 테스트가 만든 행은 끝날 때 지운다.
 */
@SpringBootTest
@EmbeddedKafka(
        brokerProperties = {"auto.create.topics.enable=false", "group.initial.rebalance.delay.ms=0"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class DlAnalysisFlowIntegrationTest {

    private static final String BARCODE = "8809037045013";
    private static final AtomicReference<String> DL_RESPONSE = new AtomicReference<>();
    private static final HttpServer FAKE_DL = startFakeDl();

    @TempDir
    static Path imageDir;

    @Autowired
    private DlAnalysisAcceptService acceptService;

    @Autowired
    private DlAnalysisRequestProducer producer;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private DlAnalysisRequestTopicProperties topic;

    private final UUID warehouseId = UUID.randomUUID();
    private final UUID boxSpecId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final List<String> eventIds = new ArrayList<>();

    @DynamicPropertySource
    static void dlAddress(DynamicPropertyRegistry registry) {
        registry.add("chagok.dl.measure.base-url", () -> "http://127.0.0.1:" + FAKE_DL.getAddress().getPort());
    }

    @BeforeEach
    void waitUntilListenerOwnsAllPartitions() {
        // 리스너가 파티션을 배정받기 전에 보낸 메시지는 배정 후에야 처리된다. 첫 테스트가 그 지연만큼 늦어
        // 시간 초과로 실패하지 않도록, 배정이 끝난 뒤에 시작한다
        listeners.getListenerContainers()
                .forEach(container -> ContainerTestUtils.waitForAssignment(container, topic.partitions()));
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
    void deleteInsertedRows() {
        eventIds.forEach(id -> jdbc.update("delete from item_event where event_id = ?", id));
        jdbc.update("delete from product where id = ?", productId);
        jdbc.update("delete from box_spec where id = ?", boxSpecId);
        jdbc.update("delete from warehouse where id = ?", warehouseId);
    }

    @AfterAll
    static void stopFakeDl() {
        FAKE_DL.stop(0);
    }

    @Test
    void identifiedItemCopiesProductMatchIntoItemEvent() throws IOException {
        DL_RESPONSE.set("{\"status\":\"known\",\"barcode\":\"" + BARCODE + "\",\"product_name\":\"테스트 상품\"}");

        String eventId = accept();

        Map<String, Object> row = awaitFinished(eventId);
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat(row.get("product_id")).isEqualTo(productId);
        assertThat(row.get("box_spec_id")).isEqualTo(boxSpecId);
        assertThat(row.get("fill_rate_pct").toString()).isEqualTo("72.50");
        assertThat(row.get("placement")).isNull();
    }

    @Test
    void unknownItemEndsAsFailedWithoutProduct() throws IOException {
        DL_RESPONSE.set("{\"status\":\"unknown\",\"barcode\":null}");

        Map<String, Object> row = awaitFinished(accept());

        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("product_id")).isNull();
    }

    @Test
    void barcodeWithoutActiveProductEndsAsFailed() throws IOException {
        DL_RESPONSE.set("{\"status\":\"known\",\"barcode\":\"0000000000000\"}");

        Map<String, Object> row = awaitFinished(accept());

        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("box_spec_id")).isNull();
    }

    @Test
    void orphanMessageIsDroppedAndDoesNotBlockLaterRequests() throws IOException {
        DL_RESPONSE.set("{\"status\":\"known\",\"barcode\":\"" + BARCODE + "\"}");
        producer.publish(DlAnalysisRequest.of(UUID.randomUUID().toString(), warehouseId.toString(), images()));

        Map<String, Object> row = awaitFinished(accept());

        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    void messageArrivingBeforeItsRowIsRetriedInsteadOfDroppedAsOrphan() throws Exception {
        DL_RESPONSE.set("{\"status\":\"known\",\"barcode\":\"" + BARCODE + "\"}");
        String eventId = UUID.randomUUID().toString();
        eventIds.add(eventId);

        producer.publish(DlAnalysisRequest.of(eventId, warehouseId.toString(), images()));
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

    private Map<String, Object> awaitFinished(String eventId) {
        await().atMost(Duration.ofSeconds(20)).until(() -> !"PENDING".equals(statusOf(eventId)));
        return jdbc.queryForMap(
                "select status, product_id, box_spec_id, fill_rate_pct, placement from item_event where event_id = ?",
                eventId);
    }

    private String statusOf(String eventId) {
        return jdbc.queryForObject("select status from item_event where event_id = ?", String.class, eventId);
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

    private static HttpServer startFakeDl() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/measure", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = DL_RESPONSE.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
