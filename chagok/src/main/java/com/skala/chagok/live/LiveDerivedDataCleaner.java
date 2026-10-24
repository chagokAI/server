package com.skala.chagok.live;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.notification.service.DomainNotificationService;

import lombok.RequiredArgsConstructor;

/**
 * 라이브 재생이 다른 도메인에 남긴 파생 데이터를 라이브 초기화와 같은 트랜잭션에서 지운다.
 *
 * <p>초기화는 재고·사용 원장·라이브 이벤트를 되돌린다. 라이브 차감이 만든 알림처럼 그 결과로 생긴 행이 남으면,
 * 초기화 주기를 바꿀 때마다 남는 양이 달라져 「초기화 후 상태」가 하나로 정해지지 않는다. 출처가 표시된 행만 지우고,
 * 실제 물류 이벤트·사람의 조작이 만든 행은 건드리지 않는다.
 */
@Component
@RequiredArgsConstructor
class LiveDerivedDataCleaner {
    private final NamedParameterJdbcTemplate jdbc;
    private final DomainNotificationService notices;

    /**
     * 라이브 관측이 섞인 예측 작업의 결과(제안·항목·비용 예측)와 작업 행을 지운다. 지운 뒤에는 초기화 커밋 후 다시 계산한다.
     *
     * <p>사람이 그 제안으로 발주서를 만들었거나 AI 작업이 그 제안을 근거로 남겼다면, 그 묶음은 업무 기록의 근거라 남긴다.
     * 에이전트가 직접 저장한 제안은 작업이 만든 것이 아니어서(job_id 없음) 대상이 아니다.
     *
     * @return 지운 작업 수
     */
    int removeLiveForecasts(UUID warehouseId) {
        var params = Map.of("warehouseId", warehouseId);
        var jobs = jdbc.queryForList("""
                select p.job_id from procurement_proposal p
                where p.warehouse_id = :warehouseId and p.job_id is not null
                  and coalesce((p.summary->>'liveObservationCount')::bigint, 0) > 0
                  and not exists (select 1 from purchase_order o where o.proposal_id = p.id)
                  and not exists (select 1 from ai_task t where t.proposal_id = p.id)
                """, params, UUID.class);
        if (jobs.isEmpty()) return 0;
        var targets = Map.of("warehouseId", warehouseId, "jobs", jobs);
        jdbc.update("""
                delete from procurement_proposal_item where proposal_id in
                    (select id from procurement_proposal where warehouse_id = :warehouseId and job_id in (:jobs))
                """, targets);
        jdbc.update("delete from procurement_proposal where warehouse_id = :warehouseId and job_id in (:jobs)", targets);
        jdbc.update("delete from cost_forecast where warehouse_id = :warehouseId and job_id in (:jobs)", targets);
        return jdbc.update("delete from analysis_job where warehouse_id = :warehouseId and id in (:jobs)", targets);
    }

    /**
     * 표시된 알림을 지운다. 되돌린 박스가 실제 차감 때문에 여전히 안전 재고 미만이면, 그 부족을 알리던 것은 지운 표시 알림뿐이다.
     * 실제 차감은 이미 미만이던 상태라 새 알림을 만들지 않았기 때문이다. 이때 표시 없는 알림을 다시 만든다.
     *
     * @param restored 이번 초기화에서 재고를 되돌린 박스(되돌린 뒤 수량)
     * @return 지운 알림 수
     */
    int removeLiveNotifications(UUID warehouseId, List<BoxSpec> restored) {
        List<String> removed = jdbc.queryForList("""
                delete from notification n using app_user u
                where n.recipient_id = u.id and u.warehouse_id = :warehouseId and n.link like :marker
                returning n.title
                """, Map.of("warehouseId", warehouseId, "marker", "%" + DomainNotificationService.LIVE_ORIGIN_QUERY + "%"),
                String.class);
        var removedTitles = new HashSet<>(removed);
        for (BoxSpec box : restored)
            if (removedTitles.contains(DomainNotificationService.shortageTitle(box))) notices.inventory(box, false, true);
        return removed.size();
    }
}
