package com.skala.chagok.job;

import com.skala.chagok.domain.job.entity.AnalysisJob;
import com.skala.chagok.domain.job.repository.AnalysisJobRepository;
import com.skala.chagok.domain.job.repository.ObservationBaselineRepository;
import com.skala.chagok.domain.job.service.*;
import com.skala.chagok.domain.procurement.service.ProcurementQueryService;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 로컬 PostgreSQL에서 HTTP 접수 → DB 큐 → 참고 계산 → 저장 결과 조회를 검증한다. */
@SpringBootTest @AutoConfigureMockMvc
class JobFlowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JobCommandService commands;
    @Autowired JobQueueService queue;
    @Autowired JobExecutionService execution;
    @Autowired JobGenerationService generator;
    @Autowired AnalysisJobRepository jobs;
    @Autowired ProcurementQueryService queries;
    @Autowired ObservationBaselineRepository demand;
    private final UUID warehouseId = UUID.randomUUID(), otherWarehouseId = UUID.randomUUID();
    private final UUID boxId = UUID.randomUUID();
    private final String month = YearMonth.now(ZoneId.of("Asia/Seoul")).plusMonths(1).toString();

    @BeforeEach void insertFixtures() {
        jdbc.update("insert into warehouse (id, name) values (?, '작업 테스트 센터')", warehouseId);
        jdbc.update("""
                insert into box_spec (id, warehouse_id, code, name, size_class, is_active, on_hand_qty, safety_qty, unit_price_krw, version)
                values (?, ?, 'S-1', '소형 1호', 'S', true, 10, 5, 180, 0)
                """, boxId, warehouseId);
    }

    @AfterEach void removeFixtures() {
        for (UUID id : List.of(warehouseId, otherWarehouseId)) {
            jdbc.update("delete from procurement_proposal_item where proposal_id in (select id from procurement_proposal where warehouse_id = ?)", id);
            jdbc.update("delete from procurement_proposal where warehouse_id = ?", id);
            jdbc.update("delete from cost_forecast where warehouse_id = ?", id);
            jdbc.update("delete from analysis_job where warehouse_id = ?", id);
            jdbc.update("delete from item_event where warehouse_id = ?", id);
            jdbc.update("delete from item_event_daily where warehouse_id = ?", id);
            jdbc.update("delete from product where warehouse_id = ?", id);
            jdbc.update("delete from box_spec where warehouse_id = ?", id);
            jdbc.update("delete from warehouse where id = ?", id);
        }
    }

    private String base() { return "/api/v1/warehouses/" + warehouseId; }
    private void observations() {
        for (int i = 0; i < 28; i++) jdbc.update("""
                insert into item_event (event_id, occurred_at, warehouse_id, box_spec_id, status)
                values (?, ?, ?, ?, 'SUCCEEDED')
                """, UUID.randomUUID().toString(), Timestamp.from(Instant.now().minusSeconds(86400).plusSeconds(i)), warehouseId, boxId);
    }
    private UUID submit() throws Exception {
        String body = mvc.perform(post(base() + "/ai-jobs").contentType("application/json")
                        .content("{\"targetMonth\":\"" + month + "\",\"type\":\"PROCUREMENT_AND_COST\"}"))
                .andExpect(status().isAccepted()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonMapper.builder().build().readTree(body).path("data").path("id").asText());
    }

    @Test void manualAndScheduledRequestsDeduplicateAndPersistBothResults() throws Exception {
        observations();
        UUID id = submit();
        var scheduled = commands.submit(warehouseId, month, AnalysisJob.Type.PROCUREMENT_AND_COST, AnalysisJob.Trigger.SCHEDULED);
        assertThat(scheduled.id()).isEqualTo(id);
        assertThat(execution.runNext()).isTrue();
        mvc.perform(get(base() + "/jobs/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.proposalId").isNotEmpty()).andExpect(jsonPath("$.data.costForecastId").isNotEmpty());
        var proposal = queries.latestProposal(warehouseId, month).result();
        var cost = queries.latestCost(warehouseId, month).result();
        assertThat(proposal.jobId()).isEqualTo(id).isEqualTo(cost.jobId());
        assertThat(proposal.analysis()).containsEntry("method", "OBSERVATION_BASELINE");
        // 관측 일수 = 28일 전 0시부터 작업 접수 시각까지(오늘 지난 시간 포함). 밤늦게 접수하면 28일보다 1일 가깝게 길어져
        // 28건 × 월 일수 ÷ 관측 일수가 월 일수보다 작아질 수 있으므로 같은 식으로 기대값을 구한다
        var created = jobs.findById(id).orElseThrow().getCreatedAt();
        var observedFrom = created.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().minusDays(28).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        double observedDays = Duration.between(observedFrom, created).toMillis() / 86_400_000d;
        assertThat(proposal.forecastUsageQty()).isEqualTo((int) Math.ceil(28 * YearMonth.parse(month).lengthOfMonth() / observedDays - 1e-9));
        assertThat(proposal.totalQty()).isEqualTo(100);
        assertThat(proposal.totalAmountKrw()).isEqualTo(18000);
        assertThat(cost.forecast().packagingSavingsKrw()).isNull();
        assertThat(jdbc.queryForObject("select on_hand_qty from box_spec where id = ?", Integer.class, boxId)).isEqualTo(10);
        assertThat(proposal.snapshotComplete()).isTrue();
    }

    @Test void failureKeepsPreviousResultsAndRetryCreatesOneNewAttempt() throws Exception {
        observations();
        submit(); execution.runNext();
        UUID previous = queries.latestProposal(warehouseId, month).result().id();
        jdbc.update("delete from item_event where warehouse_id = ?", warehouseId);
        UUID failed = submit(); execution.runNext();
        var state = jobs.findById(failed).orElseThrow();
        assertThat(state.getStatus()).isEqualTo(AnalysisJob.Status.FAILED);
        assertThat(state.getFailureCode()).isEqualTo("INSUFFICIENT_OBSERVATIONS");
        assertThat(queries.latestProposal(warehouseId, month).result().id()).isEqualTo(previous);
        observations();
        var next = commands.retry(warehouseId, failed, state.getVersion());
        assertThat(next.id()).isNotEqualTo(failed);
        assertThat(next.retryOfJobId()).isEqualTo(failed);
        assertThat(commands.retry(warehouseId, failed, state.getVersion()).id()).isEqualTo(next.id());
        execution.runNext();
        assertThat(jobs.findById(next.id()).orElseThrow().getStatus()).isEqualTo(AnalysisJob.Status.SUCCEEDED);
    }

    @Test void expiredAttemptCannotCommitAfterRetry() throws Exception {
        observations();
        UUID id = submit();
        JobClaim stale = queue.claimNext().orElseThrow();
        assertThat(stale.id()).isEqualTo(id);
        jdbc.update("update analysis_job set started_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(600)), id);
        assertThat(queue.expireStale()).isGreaterThanOrEqualTo(1);
        var expired = jobs.findById(id).orElseThrow();
        var next = commands.retry(warehouseId, id, expired.getVersion());
        generator.generate(stale);
        assertThat(queries.latestProposal(warehouseId, month).result()).isNull();
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisJob.Status.TIMED_OUT);
        execution.runNext();
        assertThat(queries.latestProposal(warehouseId, month).result().jobId()).isEqualTo(next.id());
    }

    @Test void concurrentManualAndScheduledSubmissionCreateOnlyOneActiveJob() throws Exception {
        var ready = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { ready.await(); return commands.submit(warehouseId, month,
                    AnalysisJob.Type.PROCUREMENT_AND_COST, AnalysisJob.Trigger.MANUAL); });
            var second = executor.submit(() -> { ready.await(); return commands.submit(warehouseId, month,
                    AnalysisJob.Type.PROCUREMENT_AND_COST, AnalysisJob.Trigger.SCHEDULED); });
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS).id())
                    .isEqualTo(second.get(10, java.util.concurrent.TimeUnit.SECONDS).id());
        }
        assertThat(jdbc.queryForObject("select count(*) from analysis_job where warehouse_id = ?", Long.class, warehouseId)).isEqualTo(1);
    }

    @Test void concurrentWorkersClaimDifferentJobs() throws Exception {
        commands.submit(warehouseId, month, AnalysisJob.Type.PROCUREMENT_AND_COST, AnalysisJob.Trigger.MANUAL);
        commands.submit(warehouseId, YearMonth.parse(month).plusMonths(1).toString(),
                AnalysisJob.Type.PROCUREMENT_AND_COST, AnalysisJob.Trigger.MANUAL);
        var ready = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { ready.await(); return queue.claimNext().orElseThrow(); });
            var second = executor.submit(() -> { ready.await(); return queue.claimNext().orElseThrow(); });
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS).id())
                    .isNotEqualTo(second.get(10, java.util.concurrent.TimeUnit.SECONDS).id());
        }
    }

    private UUID box(UUID warehouse, String code, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into box_spec (id, warehouse_id, code, name, size_class, is_active, on_hand_qty, version) values (?, ?, ?, ?, 'S', ?, 0, 0)",
                id, warehouse, code, code, active);
        return id;
    }
    private UUID product(UUID warehouse, String code, UUID matchedBox, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into product (id, warehouse_id, code, name, matched_box_spec_id, is_active, version, width_mm, depth_mm, height_mm)
                values (?, ?, ?, ?, ?, ?, 0, 100, 100, 100)
                """, id, warehouse, code, code, matchedBox, active);
        return id;
    }
    private void event(UUID warehouse, UUID product, UUID box, String status, Instant at, String dlStatus) {
        jdbc.update("""
                insert into item_event (event_id, occurred_at, warehouse_id, product_id, box_spec_id, status, measurement)
                values (?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """, UUID.randomUUID().toString(), Timestamp.from(at), warehouse, product, box, status,
                dlStatus == null ? null : "{\"dlStatus\":\"" + dlStatus + "\"}");
    }

    @Test void demandPrefersCatalogBoxAndCountsKnownWaitingWithoutRewritingEvents() {
        Instant from = Instant.now().minusSeconds(3600), until = Instant.now();
        UUID alternative = box(warehouseId, "S-2", true);
        UUID item = product(warehouseId, "P-1", boxId, true);
        event(warehouseId, item, alternative, "SUCCEEDED", from, "known");
        event(warehouseId, null, alternative, "SUCCEEDED", from.plusSeconds(1), "known");
        event(warehouseId, item, null, "WAITING", from.plusSeconds(2), "known");
        event(warehouseId, item, null, "WAITING", from.plusSeconds(3), null);

        var result = demand.demand(warehouseId, from, until);

        assertThat(result.counts()).containsOnly(Map.entry(boxId, 2L), Map.entry(alternative, 1L));
        assertThat(result.observedBoxCount()).isEqualTo(1);
        assertThat(result.estimatedBoxCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from item_event where warehouse_id = ? and box_spec_id = ?",
                Long.class, warehouseId, alternative)).isEqualTo(2);
    }

    @Test void archivedDemandCountsQuantitiesKnownWaitingAndLiveWithoutRetainedRawDuplicates() {
        var day = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(5);
        var from = day.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        UUID alternative = box(warehouseId, "S-2", true);
        UUID item = product(warehouseId, "P-ARCHIVE", boxId, true);
        jdbc.update("""
                insert into item_event_daily (warehouse_id, day, product_id, box_spec_id, status, source, shipments)
                values (?, ?, ?, ?, 'SUCCEEDED', 'hist', 300)
                """, warehouseId, day, item, alternative);
        jdbc.update("""
                insert into item_event_daily (warehouse_id, day, product_id, status, failure_reason, source, shipments)
                values (?, ?, ?, 'WAITING', 'NO_AVAILABLE_BOX', 'hist', 20)
                """, warehouseId, day, item);
        event(warehouseId, item, alternative, "SUCCEEDED", from.plusSeconds(1), "known");
        // 다른 출처의 라이브 원본은 hist 집계 때문에 사라지면 안 된다.
        jdbc.update("""
                insert into item_event (event_id, occurred_at, warehouse_id, product_id, box_spec_id, status)
                values (?, ?, ?, ?, ?, 'SUCCEEDED')
                """, "live-" + UUID.randomUUID(), Timestamp.from(from.plusSeconds(2)), warehouseId, item, alternative);
        var result = demand.demand(warehouseId, from, from.plusSeconds(86400));
        assertThat(result.counts()).containsOnly(Map.entry(boxId, 321L));
        assertThat(result.estimatedBoxCount()).isEqualTo(321);
        assertThat(result.liveObservationCount()).isEqualTo(1);
    }

    @Test void demandExcludesFailedOutOfPeriodInactiveAndCrossWarehouseEvents() {
        Instant from = Instant.now().minusSeconds(3600), until = Instant.now();
        UUID inactive = box(warehouseId, "S-OFF", false);
        UUID item = product(warehouseId, "P-1", boxId, true);
        event(warehouseId, item, null, "FAILED", from, "known");
        event(warehouseId, item, null, "SUCCEEDED", from.minusSeconds(1), "known");
        event(warehouseId, item, null, "SUCCEEDED", until, "known");
        event(warehouseId, null, inactive, "SUCCEEDED", from, "known");
        event(warehouseId, product(warehouseId, "P-OFF-BOX", inactive, true), null, "SUCCEEDED", from, "known");
        event(warehouseId, product(warehouseId, "P-OFF", boxId, false), null, "SUCCEEDED", from, "known");
        event(warehouseId, null, null, "SUCCEEDED", from, "known");
        jdbc.update("insert into warehouse (id, name) values (?, '다른 센터')", otherWarehouseId);
        UUID foreignBox = box(otherWarehouseId, "S-1", true);
        UUID foreign = product(otherWarehouseId, "P-1", foreignBox, true);
        event(otherWarehouseId, foreign, foreignBox, "SUCCEEDED", from, "known");
        event(warehouseId, foreign, null, "SUCCEEDED", from, "known");
        event(warehouseId, null, foreignBox, "SUCCEEDED", from, "known");

        var result = demand.demand(warehouseId, from, until);

        assertThat(result.counts()).isEmpty();
        assertThat(result.observedBoxCount()).isZero();
        assertThat(result.estimatedBoxCount()).isZero();
    }

    @Test void storedHqReportsAreReadableWithoutAnAgentServer() throws Exception {
        jdbc.execute("create schema if not exists box_agent");
        jdbc.execute("""
                create table if not exists box_agent.hq_report (month varchar(7) primary key,
                    html text not null, data jsonb not null, summary jsonb, recommendations jsonb,
                    updated_at timestamptz not null default now())
                """);
        jdbc.update("""
                insert into box_agent.hq_report (month, html, data, summary)
                values ('1999-01', '<html>보관된 리포트</html>', '[{"warehouseId":"stored-center","ship":123}]', '["보관된 총평"]')
                """);
        try {
            mvc.perform(get("/api/v1/admin/hq-reports")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[?(@.month == '1999-01')]").isNotEmpty());
            mvc.perform(get("/api/v1/admin/hq-reports/1999-01")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.html").value("<html>보관된 리포트</html>"))
                    .andExpect(jsonPath("$.data.data[0].ship").value(123))
                    .andExpect(jsonPath("$.data.summary[0]").value("보관된 총평"))
                    .andExpect(jsonPath("$.data.recommendations").doesNotExist());
            mvc.perform(get("/api/v1/admin/hq-reports/1998-12")).andExpect(status().isNotFound());
        } finally {
            jdbc.update("delete from box_agent.hq_report where month = '1999-01'");
        }
    }

    @Test void validatesRequestsAndProtectsScopedResources() throws Exception {
        mvc.perform(post(base() + "/ai-jobs").contentType("application/json").content("{\"targetMonth\":\"2026-13\",\"type\":\"PROCUREMENT_AND_COST\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(base() + "/ai-jobs").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        UUID id = submit();
        mvc.perform(post(base() + "/jobs/" + id + "/retry").contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/warehouses/" + UUID.randomUUID() + "/jobs/" + id + "/retry")
                        .contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isNotFound());
    }
}
