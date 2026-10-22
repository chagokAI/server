package com.skala.chagok.domain.purchase.service;

import com.skala.chagok.domain.purchase.dto.response.*;
import com.skala.chagok.domain.purchase.entity.*;
import com.skala.chagok.domain.purchase.repository.*;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.web.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import jakarta.persistence.criteria.JoinType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class PurchaseOrderQueryService {
    private final PurchaseOrderRepository orders;
    private final PurchaseOrderItemRepository items;
    private final PurchaseHistorySummaryRepository summaries;
    private final WarehouseQueryGuard warehouses;
    private final ChagokProperties properties;
    private static final Map<String, String> SORTS = Map.of("createdAt", "createdAt", "deliveryDate", "requestedDeliveryDate",
            "totalAmountKrw", "totalAmountKrw");

    public PurchaseOrderPageResponse list(UUID warehouseId, LocalDate from, LocalDate to, String keyword,
                                           PurchaseOrderStatus status, int page, int size, String sort) {
        return list(warehouseId, from, to, keyword, status, page, size, sort, false);
    }

    public PurchaseOrderPageResponse list(UUID warehouseId, LocalDate from, LocalDate to, String keyword,
                                           PurchaseOrderStatus status, int page, int size, String sort, boolean historyOnly) {
        warehouses.require(warehouseId); QueryValues.dateRange(from, to);
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > 366 * 5)
            throw BusinessException.invalidField("to", "조회 기간은 최대 5년입니다.");
        Instant start = from.atStartOfDay(properties.timezone()).toInstant();
        Instant until = to.plusDays(1).atStartOfDay(properties.timezone()).toInstant();
        Specification<PurchaseOrder> specification = (root, query, cb) -> cb.and(
                cb.equal(root.get("warehouse").get("id"), warehouseId),
                cb.greaterThanOrEqualTo(root.get("createdAt"), start), cb.lessThan(root.get("createdAt"), until));
        if (keyword != null && !keyword.isBlank()) {
            String pattern = LikePatterns.containsIgnoreCase(keyword);
            specification = specification.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("poNo")), pattern, LikePatterns.ESCAPE),
                    cb.like(cb.lower(root.join("supplier", JoinType.LEFT).get("name")), pattern, LikePatterns.ESCAPE)));
        }
        if (historyOnly) specification = specification.and((root, query, cb) -> root.get("status").in(
                PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.IN_TRANSIT, PurchaseOrderStatus.RECEIVED));
        if (status != null) specification = specification.and((root, query, cb) -> cb.equal(root.get("status"), status));
        var pageable = PageRequests.of(page, size, sort, SORTS, "createdAt,desc");
        var amountSort = pageable.getSort().getOrderFor("totalAmountKrw");
        if (amountSort != null) {
            specification = specification.and((root, query, cb) -> {
                if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                    var amount = query.subquery(Long.class);
                    var line = amount.from(PurchaseOrderItem.class);
                    var price = cb.prod(line.get("qty").as(Long.class), line.get("unitPriceKrw").as(Long.class));
                    amount.select(cb.<Long>selectCase().when(cb.equal(cb.count(line), cb.count(price)), cb.sum(price))
                            .otherwise(cb.nullLiteral(Long.class))).where(cb.equal(line.get("purchaseOrder"), root));
                    query.orderBy(amountSort.isAscending() ? cb.asc(amount) : cb.desc(amount), cb.asc(root.get("id")));
                }
                return cb.conjunction();
            });
            pageable = org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        }
        var result = orders.findAll(specification, pageable);
        var ids = result.getContent().stream().map(PurchaseOrder::getId).toList();
        Map<UUID, List<PurchaseOrderItem>> grouped = ids.isEmpty() ? Map.of() : items.findByPurchaseOrderIdIn(ids).stream()
                .collect(Collectors.groupingBy(item -> item.getId().getPurchaseOrderId()));
        var totals = summaries.summarize(warehouseId, start, until, keyword).stream()
                .filter(total -> !historyOnly || Set.of("APPROVED", "IN_TRANSIT", "RECEIVED").contains(total.status())).toList();
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("ALL", totals.stream().mapToLong(PurchaseHistorySummaryRepository.StatusTotals::count).sum());
        for (PurchaseOrderStatus value : PurchaseOrderStatus.values()) counts.put(value.name(), 0L);
        totals.forEach(value -> counts.put(value.status(), value.count()));
        long samples = totals.stream().mapToLong(PurchaseHistorySummaryRepository.StatusTotals::leadSamples).sum();
        BigDecimal leadDays = totals.stream().map(PurchaseHistorySummaryRepository.StatusTotals::leadDays).reduce(BigDecimal.ZERO, BigDecimal::add);
        long incomplete = totals.stream().mapToLong(PurchaseHistorySummaryRepository.StatusTotals::incompleteAmounts).sum();
        var summary = new PurchaseOrderPageResponse.Summary(counts.get("ALL"),
                incomplete > 0 ? null : totals.stream().map(PurchaseHistorySummaryRepository.StatusTotals::amountKrw).reduce(BigDecimal.ZERO, BigDecimal::add),
                counts.get("PENDING"), samples == 0 ? null : leadDays.divide(BigDecimal.valueOf(samples), 1, RoundingMode.HALF_UP), samples,
                counts.get("APPROVED") + counts.get("IN_TRANSIT"), incomplete);
        return new PurchaseOrderPageResponse(result.stream().map(order -> PurchaseOrderPageResponse.Row.from(order,
                grouped.getOrDefault(order.getId(), List.of()))).toList(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages(), counts, summary, from, to);
    }

    public PurchaseOrderResponse get(UUID warehouseId, UUID orderId) {
        warehouses.require(warehouseId);
        PurchaseOrder order = orders.findByIdAndWarehouseId(orderId, warehouseId)
                .orElseThrow(() -> BusinessException.notFound("발주서"));
        return PurchaseOrderResponse.from(order, items.findByPurchaseOrderIdIn(List.of(orderId)));
    }
}
