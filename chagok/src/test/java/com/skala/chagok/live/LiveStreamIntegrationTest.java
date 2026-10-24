package com.skala.chagok.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 라이브 재고 스트림의 연결 응답을 로컬 PostgreSQL에서 확인한다. 연결하면 현재고를 먼저 보내고,
 * 없는 센터와 구독 상한 초과는 상태·헤더로 거절한다. 상한은 1로 낮춰 두 번째 연결에서 확인한다.
 */
@SpringBootTest(properties = {"chagok.auth.enabled=false", "chagok.live.max-subscribers-per-warehouse=1"})
@AutoConfigureMockMvc
class LiveStreamIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.skala.chagok.domain.inventory.service.InventoryCommandService inventory;
    private final UUID warehouseId = UUID.randomUUID();

    @BeforeEach void fixtures() {
        jdbc.update("insert into warehouse (id, name) values (?, '라이브 테스트 센터')", warehouseId);
        jdbc.update("""
                insert into box_spec (id, warehouse_id, code, name, is_active, version, on_hand_qty,
                    inner_width_mm, inner_depth_mm, inner_height_mm)
                values (?, ?, 'L-1', '라이브 박스', true, 0, 7, 200, 150, 100),
                       (?, ?, 'L-OFF', '쓰지 않는 박스', false, 0, 3, 100, 100, 100)
                """, UUID.randomUUID(), warehouseId, UUID.randomUUID(), warehouseId);
    }

    @AfterEach void removeFixtures() {
        jdbc.update("delete from inventory_movement where warehouse_id = ?", warehouseId);
        jdbc.update("delete from box_spec where warehouse_id = ?", warehouseId);
        jdbc.update("delete from warehouse where id = ?", warehouseId);
    }

    private String stream(UUID warehouse) { return "/api/v1/warehouses/" + warehouse + "/live-stream"; }

    @Test void connectingSendsTheCurrentStockOfActiveBoxesFirst() throws Exception {
        var result = mvc.perform(get(stream(warehouseId)).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).startsWith("event:snapshot");
        assertThat(body).contains("\"code\":\"L-1\"").contains("\"onHandQty\":7").doesNotContain("L-OFF");
    }

    @Test void unknownWarehouseIsNotFound() throws Exception {
        mvc.perform(get(stream(UUID.randomUUID())).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(handler().handlerType(LiveStreamController.class))
                .andExpect(handler().methodName("stream"))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isNotFound());
    }

    @Test void subscribersBeyondTheWarehouseLimitAreToldToRetryLater() throws Exception {
        mvc.perform(get(stream(warehouseId)).accept(MediaType.TEXT_EVENT_STREAM)).andExpect(request().asyncStarted());

        mvc.perform(get(stream(warehouseId)).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "10"));
    }

    @Test void receivingStockPublishesANewSnapshotAfterCommit() throws Exception {
        var response = mvc.perform(get(stream(warehouseId)).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn().getResponse();
        UUID box = jdbc.queryForObject("select id from box_spec where warehouse_id=? and is_active=true", UUID.class, warehouseId);
        inventory.receive(warehouseId, new com.skala.chagok.domain.inventory.dto.InventoryReceiptRequest(
                UUID.randomUUID(), box, 0, null, 5, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")), "live receipt"));
        // 화면 전송은 센터 전송 스레드에서 비동기로 일어난다. 입고 snapshot은 장면을 지우지 않도록 사유를 함께 보낸다.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(response.getContentAsString()).contains("\"onHandQty\":12", "\"reason\":\"stock\""));
        assertThat(response.getContentAsString()).contains("\"reason\":\"connect\"");
        assertThat(response.getContentAsString().split("event:snapshot", -1)).hasSize(3);
    }
}
