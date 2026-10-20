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

    /** 결과 두 개와 작업 성공을 원자적으로 반영한다. version 충돌 시 생성 결과도 함께 롤백된다. */
    @Transactional(timeout = 60, isolation = Isolation.REPEATABLE_READ)
    public void generate(JobClaim claim) {
        var job = jobs.findById(claim.id()).orElseThrow();
        if (job.getStatus() != AnalysisJob.Status.RUNNING || !Objects.equals(job.getVersion(), claim.version())) return;
        var specs = boxes.findByWarehouseIdAndIsActiveTrue(job.getWarehouse().getId());
        if (specs.isEmpty()) throw new JobCalculationException("NO_ACTIVE_BOX_SPECS", "활성 박스 규격이 없습니다.");
        var until = job.getCreatedAt();
        var from = until.minus(properties.lookbackDays(), ChronoUnit.DAYS);
        var counts = observations.counts(job.getWarehouse().getId(), from, until);
        long sampleCount = specs.stream().mapToLong(spec -> counts.getOrDefault(spec.getId(), 0L)).sum();
        if (sampleCount == 0) throw new JobCalculationException("INSUFFICIENT_OBSERVATIONS", "계산 기간에 활성 규격의 성공 관측이 없습니다.");
        YearMonth month = YearMonth.parse(job.getTargetMonth());
        var lines = specs.stream().map(spec -> calculator.calculate(spec, counts.getOrDefault(spec.getId(), 0L), month)).toList();
        Map<String, Object> summary = Map.of("method", "OBSERVATION_BASELINE", "sourceStart", from.toString(),
                "sourceEndExclusive", until.toString(), "lookbackDays", properties.lookbackDays(),
                "sampleCount", sampleCount, "orderUnit", properties.orderUnit(),
                "insights", List.of("성공 관측의 일평균을 대상 월 일수로 환산한 참고값입니다. 실제 박스 소모량 예측이 아닙니다."));
        var proposal = proposals.saveAndFlush(ProcurementProposal.builder().warehouse(job.getWarehouse())
                .targetMonth(job.getTargetMonth()).jobId(job.getId()).dataAsOf(until).summary(summary).build());
        items.saveAll(lines.stream().map(line -> ProcurementProposalItem.builder().proposal(proposal).boxSpec(line.spec())
                .onHandQty(line.spec().getOnHandQty()).safetyQty(line.spec().getSafetyQty())
                .forecastUsageQty(line.forecastQty()).proposedQty(line.proposedQty()).unitPriceKrw(line.unitPriceKrw())
                .boxSnapshot(line.snapshot()).build()).toList());
        var result = new CostForecastResult(lines.stream().mapToLong(ObservationBaselineCalculator.Line::packagingCostKrw).sum(),
                null, lines.stream().mapToLong(ObservationBaselineCalculator.Line::purchaseCostKrw).sum(), null, null,
                lines.stream().map(line -> new CostForecastResult.Item(line.snapshot(), line.forecastQty(), line.proposedQty(),
                        line.unitPriceKrw(), line.packagingCostKrw(), line.purchaseCostKrw())).toList(),
                List.of("OBSERVATION_BASELINE: 관측 기반 참고 계산입니다. 절감액·비교값은 산출하지 않았습니다."));
        var cost = costs.saveAndFlush(CostForecast.builder().warehouse(job.getWarehouse()).jobId(job.getId())
                .targetMonth(job.getTargetMonth()).dataAsOf(until).generatedAt(jobClock.instant()).result(result).build());
        job.succeed(proposal.getId(), cost.getId(), until, jobClock.instant());
        jobs.flush();
    }
}
