package com.skala.chagok.workflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** COMMON-API-03 응답 계약. 트랜잭션은 롤백한다. */
@SpringBootTest(properties = {"chagok.auth.enabled=false", "chagok.jobs.scheduling-enabled=false"})
@AutoConfigureMockMvc @Transactional
class RefreshPolicyFlowTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test void refreshPolicyReturnsTheConfiguredIntervalAndSchedule() throws Exception {
        UUID warehouse = UUID.randomUUID();
        jdbc.update("insert into warehouse(id,name) values (?, '새로고침 테스트')", warehouse);
        mvc.perform(get("/api/v1/warehouses/" + warehouse + "/refresh-policy")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refreshAfterSeconds").value(300))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.data.analysisSchedule.enabled").value(false))
                .andExpect(jsonPath("$.data.analysisSchedule.cron").value("0 */5 * * * *"))
                .andExpect(jsonPath("$.data.analysisSchedule.nextRunAt").isEmpty())
                .andExpect(jsonPath("$.meta.refreshAfterSeconds").value(300));
    }

    @Test void unknownWarehouseIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/warehouses/" + UUID.randomUUID() + "/refresh-policy")).andExpect(status().isNotFound());
    }
}
