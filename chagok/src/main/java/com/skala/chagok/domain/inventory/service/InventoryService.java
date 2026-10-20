package com.skala.chagok.domain.inventory.service;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.box.service.BoxSpecOrdering;
import com.skala.chagok.domain.inventory.dto.InventoryItemResponse;
import com.skala.chagok.domain.inventory.dto.InventoryPageResponse;
import com.skala.chagok.domain.inventory.dto.InventoryPolicyUpdateRequest;
import com.skala.chagok.domain.inventory.dto.InventoryStatus;
import com.skala.chagok.domain.inventory.dto.InventorySummary;
import com.skala.chagok.domain.procurement.repository.ProcurementProposalItemRepository;
import com.skala.chagok.domain.procurement.repository.ProcurementProposalRepository;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.error.ErrorCode;
import com.skala.chagok.global.response.PageResponse;
import com.skala.chagok.global.web.PageRequests;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 박스 재고 관리(05 화면). 재고는 box_spec의 재고 컬럼을 그대로 읽는다.
 *
 * <p>상태(충분/주의/부족)는 공급사 리드타임까지 엮어 계산해야 해서, 센터의 활성 규격을 한 번에 읽은 뒤
 * 필터·정렬·페이지를 메모리에서 처리한다. 센터당 규격은 수십 개 수준이라 이 방식이 단순하고 충분하다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService {

    private static final Map<String, String> SORTS = Map.of(
            "boxCode", "code",
            "availableQty", "onHandQty",
            "expectedDaysToStockout", "daysToStockout");
    private static final String DEFAULT_SORT = "boxCode,asc";
    private static final String COUNT_ALL = "ALL";

    private final BoxSpecRepository boxSpecRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProcurementProposalRepository procurementProposalRepository;
    private final ProcurementProposalItemRepository procurementProposalItemRepository;
    private final ChagokProperties properties;

    private record Row(BoxSpec spec, InventoryStatus status) {
    }

    public InventoryPageResponse getInventories(UUID warehouseId, String keyword, InventoryStatus status,
                                                int page, int size, String sort) {
        requireWarehouse(warehouseId);
        Pageable pageable = PageRequests.of(page, size, sort, SORTS, DEFAULT_SORT);
        int bufferDays = properties.inventory().cautionBufferDays();

        List<Row> all = boxSpecRepository.findByWarehouseIdAndIsActiveTrue(warehouseId).stream()
                .map(spec -> new Row(spec, InventoryStatus.of(spec, bufferDays)))
                .toList();

        // counts는 검색어만 적용하고 상태 필터는 제외한다.
        List<Row> matched = all.stream().filter(row -> matchesKeyword(row.spec(), keyword)).toList();
        List<Row> filtered = matched.stream()
                .filter(row -> status == null || row.status() == status)
                .sorted(Comparator.comparing(Row::spec, BoxSpecOrdering.of(pageable.getSort())))
                .toList();

        ZoneId zone = properties.timezone();
        PageResponse<InventoryItemResponse> body = PageResponse.of(PageRequests.slice(filtered, pageable),
                row -> InventoryItemResponse.from(row.spec(), row.status(), zone), countByStatus(matched));
        return InventoryPageResponse.of(body, summarize(warehouseId, all));
    }

    @Transactional
    public InventoryItemResponse updatePolicy(UUID warehouseId, UUID inventoryId, InventoryPolicyUpdateRequest request) {
        requireWarehouse(warehouseId);
        BoxSpec boxSpec = boxSpecRepository.findByIdAndWarehouseId(inventoryId, warehouseId)
                .orElseThrow(() -> BusinessException.notFound("박스 재고"));
        if (!Objects.equals(boxSpec.getVersion(), request.expectedVersion())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }

        boxSpec.updateSafetyQty(request.safetyQty());
        // @Version은 flush 시점에 올라가므로, 응답에 새 버전을 담으려면 먼저 flush한다.
        boxSpecRepository.flush();
        InventoryStatus status = InventoryStatus.of(boxSpec, properties.inventory().cautionBufferDays());
        return InventoryItemResponse.from(boxSpec, status, properties.timezone());
    }

    private InventorySummary summarize(UUID warehouseId, List<Row> all) {
        long totalOnHand = all.stream()
                .mapToLong(row -> row.spec().getOnHandQty() == null ? 0 : row.spec().getOnHandQty())
                .sum();
        List<String> shortageCodes = all.stream()
                .filter(row -> row.status() == InventoryStatus.SHORTAGE)
                .map(row -> row.spec().getCode())
                .sorted(Comparator.nullsLast(Comparator.naturalOrder()))
                .toList();

        OptionalDouble avgDays = all.stream()
                .map(row -> row.spec().getDaysToStockout())
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average();

        Instant lastUpdated = all.stream()
                .map(row -> row.spec().getStockUpdatedAt())
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        return new InventorySummary(
                totalOnHand,
                all.size(),
                shortageCodes.size(),
                shortageCodes,
                weeklyForecastUsage(warehouseId),
                null,
                avgDays.isPresent() ? BigDecimal.valueOf(avgDays.getAsDouble()).setScale(1, RoundingMode.HALF_UP) : null,
                properties.inventory().targetDaysToStockout(),
                lastUpdated == null ? null : lastUpdated.atZone(properties.timezone()).toOffsetDateTime()
        );
    }

    /** 이번 달 제안서의 예상 소모량(월) × 7 ÷ 이번 달 일수. 제안서가 없으면 null. */
    private Integer weeklyForecastUsage(UUID warehouseId) {
        YearMonth thisMonth = YearMonth.now(properties.timezone());
        return procurementProposalRepository
                .findFirstByWarehouseIdAndTargetMonthOrderByGeneratedAtDesc(warehouseId, thisMonth.toString())
                .map(proposal -> procurementProposalItemRepository.sumForecastUsageQty(proposal.getId()))
                .map(monthly -> BigDecimal.valueOf(monthly).multiply(BigDecimal.valueOf(7))
                        .divide(BigDecimal.valueOf(thisMonth.lengthOfMonth()), 0, RoundingMode.HALF_UP)
                        .intValue())
                .orElse(null);
    }

    private static Map<String, Long> countByStatus(List<Row> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put(COUNT_ALL, (long) rows.size());
        for (InventoryStatus status : InventoryStatus.values()) {
            counts.put(status.name(), rows.stream().filter(row -> row.status() == status).count());
        }
        return counts;
    }

    /** 박스 규격 코드 또는 이름 부분 일치(대소문자 무시) */
    private static boolean matchesKeyword(BoxSpec spec, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String needle = keyword.trim().toLowerCase(Locale.ROOT);
        return contains(spec.getCode(), needle) || contains(spec.getName(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private void requireWarehouse(UUID warehouseId) {
        if (!warehouseRepository.existsById(warehouseId)) {
            throw BusinessException.notFound("센터");
        }
    }
}
