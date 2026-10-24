package com.skala.chagok.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.skala.chagok.dlanalysis.DlAnalysisResultRecorder;

/**
 * 라이브 재생이 남긴 파생 데이터가 라이브 초기화와 함께 되돌아가는지 로컬 PostgreSQL에서 확인한다.
 *
 * <p>초기화는 재고·사용 원장·라이브 이벤트를 되돌린다. 그런데 라이브 차감이 안전 재고를 밑돌며 만든 알림은 남아,
 * 초기화 주기마다 같은 알림이 쌓였다. 라이브가 만든 알림에는 출처를 남기고 초기화가 그것만 지워야 한다.
 * 같은 차감이라도 실제 물류 이벤트가 만든 알림은 업무 기록이므로 남는다.
 */
@SpringBootTest(properties = {"chagok.auth.enabled=false"})
class LiveDerivedDataIntegrationTest {
    private static final String BARCODE = "8801234500017";

    @Autowired JdbcTemplate jdbc;
    @Autowired DlAnalysisResultRecorder recorder;
    @Autowired LiveResetService reset;
    @Autowired com.skala.chagok.domain.job.service.JobCommandService jobs;
    @Autowired com.skala.chagok.domain.job.service.JobExecutionService jobRunner;

    private final UUID warehouseId = UUID.randomUUID();
    private final UUID boxId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach void fixtures() {
        jdbc.update("insert into warehouse (id, name) values (?, '파생 데이터 테스트 센터')", warehouseId);
        // 현재고 10, 안전 재고 10. 한 개만 써도 안전 재고 미만이 되어 알림이 생긴다
        jdbc.update("""
                insert into box_spec (id, warehouse_id, code, name, is_active, version, on_hand_qty, safety_qty, alert_enabled,
                    unit_price_krw, inner_width_mm, inner_depth_mm, inner_height_mm)
                values (?, ?, 'D-1', '파생 테스트 박스', true, 0, 10, 10, true, 300, 239, 80, 34)
                """, boxId, warehouseId);
        jdbc.update("""
                insert into product (id, warehouse_id, code, name, matched_box_spec_id, fill_rate_pct, is_active, version, width_mm, depth_mm, height_mm)
                values (?, ?, ?, '파생 테스트 상품', ?, 72.50, true, 0, 239, 80, 34)
                """, productId, warehouseId, BARCODE, boxId);
        jdbc.update("insert into app_user (id, warehouse_id, employee_no, name, role) values (?, ?, ?, '수신자', 'OPERATOR')",
                userId, warehouseId, "D" + userId.toString().substring(0, 8));
    }

    @AfterEach void removeFixtures() {
        jdbc.update("delete from purchase_order where warehouse_id = ?", warehouseId);
        jdbc.update("delete from procurement_proposal_item where proposal_id in (select id from procurement_proposal where warehouse_id = ?)", warehouseId);
        jdbc.update("delete from procurement_proposal where warehouse_id = ?", warehouseId);
        jdbc.update("delete from cost_forecast where warehouse_id = ?", warehouseId);
        jdbc.update("delete from analysis_job where warehouse_id = ?", warehouseId);
        jdbc.update("delete from notification where recipient_id = ?", userId);
        jdbc.update("delete from app_user where id = ?", userId);
        jdbc.update("delete from inventory_movement where warehouse_id = ?", warehouseId);
        jdbc.update("delete from item_event where warehouse_id = ?", warehouseId);
        jdbc.update("delete from product where id = ?", productId);
        jdbc.update("delete from box_spec where warehouse_id = ?", warehouseId);
        jdbc.update("delete from warehouse where id = ?", warehouseId);
    }

    @Test void safetyStockAlertFromLiveConsumptionIsRemovedByTheReset() {
        String eventId = pending("live-" + UUID.randomUUID());

        recorder.recordIdentified(eventId, warehouseId, BARCODE);
        assertThat(inventoryAlertLinks()).singleElement().asString().contains("origin=live");

        reset.reset(warehouseId);

        assertThat(inventoryAlertLinks()).isEmpty();
        assertThat(stock()).isEqualTo(10);
    }

    @Test void safetyStockAlertFromARealEventIsKeptByTheReset() {
        String eventId = pending(UUID.randomUUID().toString());

        recorder.recordIdentified(eventId, warehouseId, BARCODE);
        reset.reset(warehouseId);

        assertThat(inventoryAlertLinks()).singleElement().asString().doesNotContain("origin=live");
        assertThat(stock()).isEqualTo(9);
    }

    @Test void shortageThatOutlivesTheResetKeepsAnUnmarkedAlert() {
        // 라이브 차감으로 10→9(표시 알림), 이어진 실제 차감으로 9→8(이미 미만이라 새 알림 없음). 초기화가 라이브분만 되돌려도
        // 9로 여전히 미만이므로, 표시 알림을 지우면 실제 부족을 알리는 알림이 하나 남아야 한다
        recorder.recordIdentified(pending("live-" + UUID.randomUUID()), warehouseId, BARCODE);
        recorder.recordIdentified(pending(UUID.randomUUID().toString()), warehouseId, BARCODE);
        assertThat(inventoryAlertLinks()).singleElement().asString().contains("origin=live");

        reset.reset(warehouseId);

        assertThat(stock()).isEqualTo(9);
        assertThat(inventoryAlertLinks()).containsExactly("/boxes/inventory");
    }

    @Test void forecastsComputedWithLiveDemandAreRemovedAndRecomputedByTheReset() {
        // 실제 물류 이력 1건과 라이브 1건. 초기화 뒤 다시 계산하면 실제 이력만 남는다
        recorder.recordIdentified(pending(UUID.randomUUID().toString()), warehouseId, BARCODE);
        recorder.recordIdentified(pending("live-" + UUID.randomUUID()), warehouseId, BARCODE);
        var thisMonth = java.time.YearMonth.now(java.time.ZoneId.of("Asia/Seoul"));
        UUID kept = forecast(thisMonth.toString());
        UUID removed = forecast(thisMonth.plusMonths(1).toString());
        assertThat(liveObservations(kept)).isEqualTo(1);
        assertThat(liveObservations(removed)).isEqualTo(1);
        assertThat(jdbc.queryForList("select link from notification where recipient_id = ? and category = 'AI'", String.class, userId))
                .hasSize(2).allSatisfy(link -> assertThat(link).contains("origin=live"));
        // 사람이 이 제안으로 발주서를 만들었다면 그 근거는 업무 기록이므로 남긴다
        jdbc.update("insert into purchase_order (id, warehouse_id, proposal_id, created_at, version) values (?, ?, ?, now(), 0)",
                UUID.randomUUID(), warehouseId, kept);

        reset.reset(warehouseId);

        assertThat(count("select count(*) from procurement_proposal where id = ?", removed)).isZero();
        assertThat(count("select count(*) from procurement_proposal_item where proposal_id = ?", removed)).isZero();
        assertThat(count("select count(*) from procurement_proposal where id = ?", kept)).isEqualTo(1);
        assertThat(count("""
                select count(*) from cost_forecast c join procurement_proposal p on p.job_id = c.job_id where p.id = ?
                """, kept)).isEqualTo(1);
        assertThat(count("select count(*) from analysis_job where warehouse_id = ? and status = 'SUCCEEDED'", warehouseId)).isEqualTo(1);
        assertThat(jdbc.queryForList("select link from notification where recipient_id = ? and category = 'AI'", String.class, userId))
                .isEmpty();
        // 초기화 직후 두 달의 예측을 다시 접수하고, 다시 계산한 결과에는 라이브 관측이 없다
        assertThat(count("select count(*) from analysis_job where warehouse_id = ? and status = 'PENDING'", warehouseId)).isEqualTo(2);
        runPendingJobs();
        assertThat(jdbc.queryForList("""
                select (summary->>'liveObservationCount')::bigint from procurement_proposal
                where warehouse_id = ? and id <> ?
                """, Long.class, warehouseId, kept)).hasSize(2).containsOnly(0L);
    }

    private UUID forecast(String month) {
        var job = jobs.submit(warehouseId, month, com.skala.chagok.domain.job.entity.AnalysisJob.Type.PROCUREMENT_AND_COST,
                com.skala.chagok.domain.job.entity.AnalysisJob.Trigger.MANUAL);
        runPendingJobs();
        return jdbc.queryForObject("select proposal_id from analysis_job where id = ?", UUID.class, job.id());
    }

    @Test void oneMonthFailingToResubmitDoesNotCancelTheOtherMonth() {
        // 이번 달 작업 저장만 DB에서 실패시킨다. 두 달을 한 트랜잭션으로 접수하면 다음 달 접수까지 함께 롤백된다
        String month = java.time.YearMonth.now(java.time.ZoneId.of("Asia/Seoul")).toString();
        jdbc.execute("""
                create or replace function reject_live_test_job() returns trigger as $$
                begin
                    if new.warehouse_id = '%s' and new.target_month = '%s' then raise exception 'rejected for test'; end if;
                    return new;
                end $$ language plpgsql
                """.formatted(warehouseId, month));
        jdbc.execute("create trigger reject_live_test_job before insert on analysis_job for each row execute function reject_live_test_job()");
        try {
            reset.reset(warehouseId);
        } finally {
            jdbc.execute("drop trigger if exists reject_live_test_job on analysis_job");
            jdbc.execute("drop function if exists reject_live_test_job()");
        }
        assertThat(jdbc.queryForList("select target_month from analysis_job where warehouse_id = ? and status = 'PENDING'",
                String.class, warehouseId)).containsExactly(java.time.YearMonth.parse(month).plusMonths(1).toString());
    }

    private void runPendingJobs() {
        for (int i = 0; i < 20 && count("select count(*) from analysis_job where warehouse_id = ? and status in ('PENDING','RUNNING')",
                warehouseId) > 0; i++) jobRunner.runNext();
    }

    private Long liveObservations(UUID proposalId) {
        return jdbc.queryForObject("select (summary->>'liveObservationCount')::bigint from procurement_proposal where id = ?",
                Long.class, proposalId);
    }

    private long count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Long.class, arg);
    }

    private String pending(String eventId) {
        jdbc.update("insert into item_event (event_id, occurred_at, warehouse_id, status) values (?, now(), ?, 'PENDING')",
                eventId, warehouseId);
        return eventId;
    }

    private List<String> inventoryAlertLinks() {
        return jdbc.queryForList("select link from notification where recipient_id = ? and category = 'INVENTORY'",
                String.class, userId);
    }

    private Integer stock() {
        return jdbc.queryForObject("select on_hand_qty from box_spec where id = ?", Integer.class, boxId);
    }
}
