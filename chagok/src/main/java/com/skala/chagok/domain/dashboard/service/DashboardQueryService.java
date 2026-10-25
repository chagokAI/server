package com.skala.chagok.domain.dashboard.service;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.dashboard.dto.DashboardResponse;
import com.skala.chagok.domain.inventory.service.InventoryService;
import com.skala.chagok.domain.procurement.dto.ProposalResponse;
import com.skala.chagok.domain.procurement.dto.CostForecastResponse;
import com.skala.chagok.domain.procurement.dto.CostForecastResult;
import com.skala.chagok.domain.procurement.dto.LatestResult;
import com.skala.chagok.domain.procurement.dto.AgentPlanProjection;
import com.skala.chagok.domain.box.service.BoxSizeClassifier;
import com.skala.chagok.domain.inventory.dto.InventorySummary;
import com.skala.chagok.domain.procurement.service.ProcurementQueryService;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.web.*;
import java.math.*;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class DashboardQueryService {
    private final BoxSpecRepository boxes;
    private final InventoryService inventory;
    private final ProcurementQueryService forecasts;
    private final WarehouseQueryGuard warehouses;
    private final ChagokProperties properties;
    private final BoxSizeClassifier sizes;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public DashboardResponse get(UUID warehouseId, String targetMonth) {
        warehouses.require(warehouseId);
        YearMonth current = YearMonth.now(clock.withZone(properties.timezone()));
        String month = QueryValues.month(targetMonth, current.plusMonths(1)).toString();
        List<BoxSpec> specs = boxes.findByWarehouseIdAndIsActiveTrue(warehouseId);
        var summary = inventory.summarize(warehouseId);
        var proposal = forecasts.latestProposal(warehouseId, month, "AUTO");
        // 다음 달 제안서를 만들기 전(월말 전)에는 이번 달 제안서의 사용 계획·주문으로 대신 보여 준다(임시 표시, 기준 문구에 적는다)
        String fallbackFrom = null;
        if ((proposal == null || proposal.result() == null) && month.equals(current.plusMonths(1).toString())) {
            var previous = forecasts.latestProposal(warehouseId, current.toString(), "AUTO");
            if (previous != null && previous.result() != null) {
                proposal = previous;
                fallbackFrom = current.toString();
            }
        }
        var plan = AgentPlanProjection.from(proposal == null ? null : proposal.result(), sizes);
        String basisNote = fallbackFrom == null ? null
                : Integer.parseInt(fallbackFrom.substring(5)) + "월 제안서 기준(" + Integer.parseInt(month.substring(5))
                  + "월 제안서 생성 전 임시)";
        Long demand = plan.totalUsage();
        var referenceCost = forecasts.latestCost(warehouseId, month);
        Map<String, Integer> monthly = new HashMap<>();
        plan.usage().forEach(row -> monthly.put(row.code(), row.qty()));
        // 과거 제안에 전체 사용 계획이 없으면 저장된 참고 계산을 안전재고 일수 계산의 근거로 쓴다.
        if (referenceCost.result() != null && referenceCost.result().forecast() != null)
            referenceCost.result().forecast().items().forEach(row -> {
                if (row.forecastUsageQty() != null) monthly.putIfAbsent(row.boxSpec().code(), row.forecastUsageQty());
            });
        Map<UUID, Integer> safety = new HashMap<>();
        for (var box : specs) {
            Integer qty = box.getSafetyQty();
            if (qty == null && box.getSafetyDays() != null && monthly.containsKey(box.getCode()))
                qty = BigDecimal.valueOf(monthly.get(box.getCode())).multiply(BigDecimal.valueOf(box.getSafetyDays()))
                        .divide(BigDecimal.valueOf(YearMonth.parse(month).lengthOfMonth()), 0, RoundingMode.CEILING).intValueExact();
            if (qty != null) safety.put(box.getId(), qty);
        }
        Map<String, long[]> stocks = new TreeMap<>();
        specs.forEach(box -> {
            long[] values = stocks.computeIfAbsent(size(box.getSizeClass(), box.getInnerWidthMm(), box.getInnerDepthMm(),
                    box.getInnerHeightMm()), key -> new long[4]);
            values[0] += qty(box.getOnHandQty()); values[1] += qty(safety.get(box.getId()));
            if (box.getOnHandQty() == null) values[2]++;
            if (!safety.containsKey(box.getId())) values[3]++;
        });
        var stockBySize = stocks.entrySet().stream().sorted(Map.Entry.comparingByKey(sizeOrder()))
                .map(e -> new DashboardResponse.StockBySize(e.getKey(), e.getValue()[2] > 0 ? null : e.getValue()[0], e.getValue()[3] > 0 ? null : e.getValue()[1])).toList();
        var shortages = specs.stream().filter(box -> box.getOnHandQty() != null && safety.containsKey(box.getId()) && box.getOnHandQty() < safety.get(box.getId()))
                .sorted(Comparator.comparing(BoxSpec::getCode))
                .map(box -> new DashboardResponse.Shortage(box.getId(), box.getCode(), box.getName(), qty(box.getOnHandQty()),
                        safety.get(box.getId()), safety.get(box.getId()) - qty(box.getOnHandQty()), box.getDaysToStockout())).toList();
        int unknownSafety = (int) specs.stream().filter(box -> !safety.containsKey(box.getId())).count();
        int unknownInventory = (int) specs.stream().filter(box -> box.getOnHandQty() == null || !safety.containsKey(box.getId())).count();
        summary = new InventorySummary(summary.totalOnHandQty(), summary.activeSpecCount(), shortages.size(),
                shortages.stream().map(DashboardResponse.Shortage::code).toList(), summary.weeklyForecastUsageQty(),
                summary.weeklyForecastChangePct(), summary.avgDaysToStockout(), summary.targetDaysToStockout(),
                summary.lastUpdatedAt(), summary.forecastSource(), unknownInventory);
        Map<String, Long> demandBySize = new TreeMap<>();
        plan.usage().forEach(row -> demandBySize.merge(sizeClass(row.sizeClass()), (long) row.qty(), Long::sum));
        final Long totalDemand = demand;
        var distribution = demandBySize.entrySet().stream().sorted(Map.Entry.comparingByKey(sizeOrder()))
                .map(e -> new DashboardResponse.DemandBySize(e.getKey(), e.getValue(), totalDemand == 0 ? null
                        : BigDecimal.valueOf(e.getValue()).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalDemand), 2, RoundingMode.HALF_UP))).toList();
        // 다음 달 말 예상 재고만 계산한다. 다른 대상 월은 중간 월 입출고를 알 수 없어 null이다.
        var projection = month.equals(current.plusMonths(1).toString()) && demand != null && proposal.result().totalQty() != null
                && specs.stream().allMatch(s -> s.getOnHandQty() != null)
                ? project(warehouseId, YearMonth.parse(month), summary.totalOnHandQty(), proposal.result(), demand,
                        safety.size() == specs.size() ? safety.values().stream().mapToLong(Integer::longValue).sum() : null,
                        stocks, demandBySize)
                : null;
        Long projected = projection == null ? null : projection.endQty();
        return new DashboardResponse(current.toString(), month, summary, stockBySize, shortages, projected,
                new DashboardResponse.DemandForecast(proposal, demand, distribution),
                currentCost(warehouseId, current), targetCost(month, referenceCost, proposal == null ? null : proposal.result(), basisNote),
                new DashboardResponse.PlanMetrics(basisNote == null ? plan.basis() : basisNote + " · " + plan.basis(),
                        plan.boxCostKrw(), plan.monthlySavingsKrw(),
                        proposal == null || proposal.result() == null ? null : proposal.result().totalAmountKrw(),
                        plan.savingsBasis(), unknownSafety),
                projection);
    }

    /**
     * 이번 달 비용(포장비·절감). 승인된 AI 제안서가 있으면 포장비 = 그 제안서의 사용 계획 × 단가,
     * 절감 = 본부 리포트의 이번 달 실제 절감(실제 박스 배정 기준, 30일 기준)으로 채운다. 없으면 참고 계산 비용 예측 그대로.
     * 응답 형식은 참고 계산과 같아 화면은 그대로 읽는다
     */
    private LatestResult<CostForecastResponse> currentCost(UUID warehouseId, YearMonth current) {
        var reference = forecasts.latestCost(warehouseId, current.toString());
        var latest = forecasts.latestProposal(warehouseId, current.toString(), "AUTO");
        var proposal = latest == null ? null : latest.result();
        Long saving = actualSaving(warehouseId, current);
        if (proposal == null && saving == null) return reference;
        var plan = proposal == null ? null : AgentPlanProjection.from(proposal, sizes);
        Long cost = plan != null && plan.boxCostKrw() != null ? plan.boxCostKrw() : forecastOf(reference, CostForecastResult::packagingCostKrw);
        return merged(current.toString(), reference, proposal, cost,
                saving != null ? saving : forecastOf(reference, CostForecastResult::packagingSavingsKrw),
                forecastOf(reference, CostForecastResult::purchaseCostKrw), "AI 제안서 사용 계획 · 본부 리포트 실제 절감 기준");
    }

    /** 다음 달 발주 예정 금액 = 승인된 AI 제안서 금액(화면의 발주 수량과 같은 제안서). 없으면 참고 계산 그대로 */
    private LatestResult<CostForecastResponse> targetCost(String month, LatestResult<CostForecastResponse> reference,
                                                         ProposalResponse proposal, String basisNote) {
        if (proposal == null || proposal.totalAmountKrw() == null) return reference;
        return merged(month, reference, proposal, forecastOf(reference, CostForecastResult::packagingCostKrw),
                forecastOf(reference, CostForecastResult::packagingSavingsKrw), proposal.totalAmountKrw(),
                basisNote == null ? "AI 제안서 주문 금액 기준" : basisNote + " · AI 제안서 주문 금액");
    }

    private LatestResult<CostForecastResponse> merged(String month, LatestResult<CostForecastResponse> reference,
                                                      ProposalResponse proposal, Long cost, Long saving, Long purchase,
                                                      String basis) {
        var base = reference == null || reference.result() == null ? null : reference.result();
        var old = base == null ? null : base.forecast();
        var result = new CostForecastResult(cost, saving, purchase, old == null ? null : old.previousMonth(),
                old == null ? null : old.previousYear(), old == null ? List.of() : old.items(), List.of(basis));
        var response = new CostForecastResponse(proposal != null ? proposal.id() : base == null ? null : base.id(), null, month,
                proposal != null ? proposal.dataAsOf() : base == null ? null : base.dataAsOf(),
                proposal != null ? proposal.generatedAt() : base == null ? null : base.generatedAt(), "KRW", false, result);
        return LatestResult.of(month, response, reference == null ? null : reference.latestJob());
    }

    private static Long forecastOf(LatestResult<CostForecastResponse> value, java.util.function.Function<CostForecastResult, Long> field) {
        return value == null || value.result() == null || value.result().forecast() == null ? null
                : field.apply(value.result().forecast());
    }

    /** 본부 리포트(box_agent.hq_report)의 그 달 그 센터 실제 절감. 데이터가 있는 일수로 나눠 30일로 맞춘다. 없으면 null */
    private Long actualSaving(UUID warehouseId, YearMonth month) {
        try {
            var rows = jdbc.queryForList("""
                    select (r->>'saving')::numeric as saving, (r->>'days')::numeric as days
                    from box_agent.hq_report h, jsonb_array_elements(h.data) r
                    where h.month = :month and r->>'warehouseId' = :wh
                    """, Map.of("month", month.toString(), "wh", warehouseId.toString()));
            if (rows.isEmpty() || rows.getFirst().get("saving") == null) return null;
            BigDecimal saving = (BigDecimal) rows.getFirst().get("saving");
            BigDecimal days = rows.getFirst().get("days") == null ? BigDecimal.valueOf(month.lengthOfMonth())
                    : (BigDecimal) rows.getFirst().get("days");
            return days.signum() <= 0 ? null : saving.multiply(BigDecimal.valueOf(30)).divide(days, 0, RoundingMode.HALF_UP).longValue();
        } catch (org.springframework.dao.DataAccessException e) {
            return null;                              // 본부 리포트 테이블이 없는 DB(에이전트 미설치·테스트)
        }
    }

    /**
     * 다음 달 말 예상 재고. 지금 재고를 다음 달 1일 재고로 쓰면 이번 달 남은 기간의 소모만큼 많게 나온다.
     * 남은 소모는 다음 달 일평균으로 추정하고, 아직 입고되지 않은 다른 발주는 더한다. 다음 달 제안으로 만든 발주는
     * 제안 주문 수량에 이미 들어 있으므로 입고 예정에서 빼고, 이미 입고된 만큼은 지금 재고에 들어 있어 주문 수량에서 뺀다.
     */
    private DashboardResponse.ProjectedStock project(UUID warehouseId, YearMonth month, long onHand, ProposalResponse proposal,
                                                     long demand, Long safety, Map<String, long[]> stockBySize,
                                                     Map<String, Long> demandBySize) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(properties.timezone()));
        ZonedDateTime start = month.atDay(1).atStartOfDay(properties.timezone());
        BigDecimal days = BigDecimal.valueOf(Math.max(0, Duration.between(now, start).toMinutes()))
                .divide(BigDecimal.valueOf(24 * 60), 2, RoundingMode.HALF_UP);
        long consume = BigDecimal.valueOf(demand).multiply(days)
                .divide(BigDecimal.valueOf(month.lengthOfMonth()), 0, RoundingMode.HALF_UP).longValue();
        var params = Map.<String, Object>of("wh", warehouseId, "month", month.toString());
        long inbound = qty(jdbc.queryForObject("""
                select coalesce(sum(i.qty), 0) from purchase_order po join purchase_order_item i on i.purchase_order_id = po.id
                left join procurement_proposal pp on pp.id = po.proposal_id
                where po.warehouse_id = :wh and po.status in ('APPROVED', 'IN_TRANSIT')
                    and (pp.target_month is null or trim(pp.target_month) <> :month)
                """, params, Long.class));
        long received = proposal.id() == null ? 0 : qty(jdbc.queryForObject("""
                select coalesce(sum(i.qty), 0) from purchase_order po join purchase_order_item i on i.purchase_order_id = po.id
                where po.proposal_id = :proposal and po.status = 'RECEIVED'
                """, Map.of("proposal", proposal.id()), Long.class));
        long order = Math.max(0, proposal.totalQty() - received);
        return new DashboardResponse.ProjectedStock(onHand, consume, inbound, order, demand, safety,
                onHand - consume + inbound + order - demand, days,
                projectBySize(warehouseId, month, proposal, days, stockBySize, demandBySize));
    }

    /** 크기별 같은 식. 입고 예정·이미 입고된 제안 발주는 규격 크기로 묶고, 남은 주문은 제안 품목 크기로 묶는다 */
    private List<DashboardResponse.SizeProjection> projectBySize(UUID warehouseId, YearMonth month, ProposalResponse proposal,
                                                                 BigDecimal days, Map<String, long[]> stockBySize,
                                                                 Map<String, Long> demandBySize) {
        Map<String, Long> inbound = bySize("""
                select b.size_class as size, b.inner_width_mm as w, b.inner_depth_mm as d, b.inner_height_mm as h,
                    coalesce(sum(i.qty), 0) as qty from purchase_order po
                join purchase_order_item i on i.purchase_order_id = po.id join box_spec b on b.id = i.box_spec_id
                left join procurement_proposal pp on pp.id = po.proposal_id
                where po.warehouse_id = :wh and po.status in ('APPROVED', 'IN_TRANSIT')
                    and (pp.target_month is null or trim(pp.target_month) <> :month)
                group by b.size_class, b.inner_width_mm, b.inner_depth_mm, b.inner_height_mm
                """, Map.of("wh", warehouseId, "month", month.toString()));
        Map<String, Long> received = proposal.id() == null ? Map.of() : bySize("""
                select b.size_class as size, b.inner_width_mm as w, b.inner_depth_mm as d, b.inner_height_mm as h,
                    coalesce(sum(i.qty), 0) as qty from purchase_order po
                join purchase_order_item i on i.purchase_order_id = po.id join box_spec b on b.id = i.box_spec_id
                where po.proposal_id = :proposal and po.status = 'RECEIVED'
                group by b.size_class, b.inner_width_mm, b.inner_depth_mm, b.inner_height_mm
                """, Map.of("proposal", proposal.id()));
        Map<String, Long> ordered = new HashMap<>();
        proposal.items().forEach(item -> ordered.merge(size(item.boxSpec().sizeClass(), item.boxSpec().innerWidthMm(),
                        item.boxSpec().innerDepthMm(), item.boxSpec().innerHeightMm()),
                (long) (item.proposedQty() == null ? 0 : item.proposedQty()), Long::sum));
        Set<String> keys = new TreeSet<>(sizeOrder());
        keys.addAll(stockBySize.keySet()); keys.addAll(demandBySize.keySet()); keys.addAll(ordered.keySet()); keys.addAll(inbound.keySet());
        return keys.stream().map(size -> {
            long start = stockBySize.containsKey(size) ? stockBySize.get(size)[0] : 0;
            long use = demandBySize.getOrDefault(size, 0L);
            long consume = BigDecimal.valueOf(use).multiply(days)
                    .divide(BigDecimal.valueOf(month.lengthOfMonth()), 0, RoundingMode.HALF_UP).longValue();
            long in = inbound.getOrDefault(size, 0L);
            long order = Math.max(0, ordered.getOrDefault(size, 0L) - received.getOrDefault(size, 0L));
            return new DashboardResponse.SizeProjection(size, start, consume, in, order, use, start - consume + in + order - use);
        }).toList();
    }

    private Map<String, Long> bySize(String sql, Map<String, ?> params) {
        Map<String, Long> out = new HashMap<>();
        jdbc.query(sql, params, rs -> {
            String stored = rs.getString("size");
            out.merge(size(stored == null || stored.isBlank() ? null : stored.trim().charAt(0), (Integer) rs.getObject("w"),
                    (Integer) rs.getObject("d"), (Integer) rs.getObject("h")), rs.getLong("qty"), Long::sum);
        });
        return out;
    }

    private static long qty(Long value) { return value == null ? 0 : value; }

    private static long qty(Integer value) { return value == null ? 0 : value; }
    private static String sizeClass(Character value) { return value == null ? "OTHER" : value.toString(); }

    /** 크기는 예측(사용 계획)과 같은 치수 기준으로 나눈다. 저장된 크기 값은 치수가 없을 때만 쓴다 */
    private String size(Character stored, Integer width, Integer depth, Integer height) {
        if (width != null && depth != null && height != null) return String.valueOf(sizes.classify(width, depth, height));
        return sizeClass(stored);
    }
    private static Comparator<String> sizeOrder() {
        return Comparator.comparingInt(value -> switch (value) { case "S" -> 0; case "M" -> 1; case "L" -> 2; default -> 3; });
    }
}
