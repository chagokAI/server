package com.skala.chagok.domain.job.service;

import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.job.config.JobProperties;
import com.skala.chagok.domain.job.entity.AnalysisJob;
import com.skala.chagok.domain.job.repository.*;
import com.skala.chagok.domain.procurement.dto.CostForecastResult;
import com.skala.chagok.domain.procurement.entity.*;
import com.skala.chagok.domain.procurement.repository.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @RequiredArgsConstructor
public class JobGenerationService {
    private final AnalysisJobRepository jobs;
    private final BoxSpecRepository boxes;
    private final ObservationBaselineRepository observations;
    private final ObservationBaselineCalculator calculator;
    private final ProcurementProposalRepository proposals;
    private final ProcurementProposalItemRepository items;
    private final CostForecastRepository costs;
    private final JobProperties properties;
    private final Clock jobClock;
    private final com.skala.chagok.domain.notification.service.DomainNotificationService notices;

    /** 결과 두 개와 작업 성공을 원자적으로 반영한다. version 충돌 시 생성 결과도 함께 롤백된다. */
    @Transactional(timeout = 60, isolation = Isolation.REPEATABLE_READ)
    public void generate(JobClaim claim) {
        var job = jobs.findById(claim.id()).orElseThrow();
        if (job.getStatus() != AnalysisJob.Status.RUNNING || !Objects.equals(job.getVersion(), claim.version())) return;
        var specs = boxes.findByWarehouseIdAndIsActiveTrue(job.getWarehouse().getId());
        if (specs.isEmpty()) throw new JobCalculationException("NO_ACTIVE_BOX_SPECS", "활성 박스 규격이 없습니다.");
        var until = job.getCreatedAt();
        var from = until.minus(properties.lookbackDays(), ChronoUnit.DAYS);
        var baseline = observations.demand(job.getWarehouse().getId(), from, until);
        var counts = baseline.counts();
        long sampleCount = specs.stream().mapToLong(spec -> counts.getOrDefault(spec.getId(), 0L)).sum();
        if (sampleCount == 0) throw new JobCalculationException("INSUFFICIENT_OBSERVATIONS", "계산 기간에 활성 규격으로 연결할 수 있는 유입 관측이 없습니다.");
        YearMonth month = YearMonth.parse(job.getTargetMonth());
        var lines = specs.stream().map(spec -> calculator.calculate(spec, counts.getOrDefault(spec.getId(), 0L), month)).toList();
        var insights = new ArrayList<>(baseline.estimatedBoxCount() > 0
                ? List.of("포장 완료·대기 유입의 일평균을 대상 월 일수로 환산한 참고 수요입니다. 실제 박스 사용 원장이 아닙니다.",
                          "관측 " + baseline.estimatedBoxCount()
                                  + "건은 현재 상품의 기본 매칭으로 필요 규격을 추정했습니다. 일시 품절에 따른 대체 사용과 구분합니다.")
                : List.of("기본 매칭이 없는 완료 이력은 관측 박스로 분류한 참고 수요입니다. 실제 박스 소모량 예측이 아닙니다."));
        if (lines.stream().anyMatch(line -> "DAYS".equals(line.safetySource())))
            insights.add("안전재고 수량이 없는 규격은 등록된 안전재고 일수 × 관측 일평균을 올림해 계산했습니다.");
        Map<String, Object> summary = new LinkedHashMap<>(Map.of("method", "OBSERVATION_BASELINE", "sourceStart", from.toString(),
                "sourceEndExclusive", until.toString(), "lookbackDays", properties.lookbackDays(),
                "sampleCount", sampleCount, "orderUnit", properties.orderUnit(),
                "observedBoxCount", baseline.observedBoxCount(), "estimatedBoxCount", baseline.estimatedBoxCount(),
                "safetyPolicy", lines.stream().map(line -> {
                    Map<String, Object> safety = new LinkedHashMap<>();
                    safety.put("boxSpecId", line.spec().getId());
                    safety.put("source", line.safetySource());
                    safety.put("qty", line.safetyQty());
                    if ("DAYS".equals(line.safetySource())) safety.put("days", line.spec().getSafetyDays());
                    return safety;
                }).toList(),
                "insights", insights));
        summary.put("liveObservationCount", baseline.liveObservationCount());
        var proposal = proposals.saveAndFlush(ProcurementProposal.builder().warehouse(job.getWarehouse())
                .targetMonth(job.getTargetMonth()).jobId(job.getId()).dataAsOf(until).summary(summary).build());
        items.saveAll(lines.stream().map(line -> ProcurementProposalItem.builder().proposal(proposal).boxSpec(line.spec())
                .onHandQty(line.spec().getOnHandQty()).safetyQty(line.safetyQty())
                .forecastUsageQty(line.forecastQty()).proposedQty(line.proposedQty()).unitPriceKrw(line.unitPriceKrw())
                .boxSnapshot(line.snapshot()).build()).toList());
        var result = new CostForecastResult(lines.stream().mapToLong(ObservationBaselineCalculator.Line::packagingCostKrw).sum(),
                null, lines.stream().mapToLong(ObservationBaselineCalculator.Line::purchaseCostKrw).sum(), null, null,
                lines.stream().map(line -> new CostForecastResult.Item(line.snapshot(), line.forecastQty(), line.proposedQty(),
                        line.unitPriceKrw(), line.packagingCostKrw(), line.purchaseCostKrw())).toList(),
                insights);
        var cost = costs.saveAndFlush(CostForecast.builder().warehouse(job.getWarehouse()).jobId(job.getId())
                .targetMonth(job.getTargetMonth()).dataAsOf(until).generatedAt(jobClock.instant()).result(result).build());
        job.succeed(proposal.getId(), cost.getId(), until, jobClock.instant());
        jobs.flush();
        // 라이브 재생 관측이 섞인 결과는 라이브 초기화가 지우고 다시 계산한다. 그 결과의 알림도 함께 지울 수 있게 표시한다
        notices.proposalCompleted(job, baseline.liveObservationCount() > 0);
    }
}
