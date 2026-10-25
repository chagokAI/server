package com.skala.chagok.domain.box.service;

import com.skala.chagok.domain.box.dto.BoxStockPageResponse;
import com.skala.chagok.domain.box.dto.BoxStockResponse;
import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.box.repository.BoxSpecSpecs;
import com.skala.chagok.domain.inventory.dto.InventoryStatus;
import com.skala.chagok.domain.inventory.service.InventoryService;
import com.skala.chagok.domain.inventory.service.InventoryUsage;
import com.skala.chagok.domain.inventory.service.InventoryUsageReader;
import com.skala.chagok.domain.product.repository.ProductRepository;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.response.PageResponse;
import com.skala.chagok.global.web.PageRequests;
import com.skala.chagok.global.web.Specs;
import com.skala.chagok.global.web.WarehouseQueryGuard;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 박스 재고/규격 관리 화면(05+06 통합)의 목록. 규격 정보·연결 상품 수·규격별 재고를 한 번에 내려준다.
 *
 * <p>재고는 box_spec의 재고 컬럼이라 규격과 같은 행에서 읽고, 상태(충분/주의/부족/미확인)와 센터 요약은
 * 재고 도메인의 규칙을 그대로 쓴다. 센터당 규격은 수십 개 수준이라 검색어까지만 DB에서 걸러 전체를 읽고
 * 탭 필터·정렬·페이지는 메모리에서 처리한다. 사용 여부 탭의 개수는 검색어만 적용한 집합 기준이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoxStockService {

    private static final Map<String, String> SORTS = Map.of(
            "boxCode", "code",
            "availableQty", "onHandQty",
            "expectedDaysToStockout", "daysToStockout");
    private static final String DEFAULT_SORT = "boxCode,asc";

    private final BoxSpecRepository boxSpecRepository;
    private final ProductRepository productRepository;
    private final InventoryUsageReader usages;
    private final InventoryService inventoryService;
    private final WarehouseQueryGuard warehouses;
    private final ChagokProperties properties;

    public BoxStockPageResponse getBoxStocks(UUID warehouseId, String keyword, Boolean active, Boolean custom,
                                             int page, int size, String sort) {
        warehouses.require(warehouseId);
        Pageable pageable = PageRequests.of(page, size, sort, SORTS, DEFAULT_SORT);
        int bufferDays = properties.inventory().cautionBufferDays();
        ZoneId zone = properties.timezone();
        InventoryUsage usage = usages.read(warehouseId);

        List<BoxSpec> matched = boxSpecRepository.findAll(
                Specs.allOf(BoxSpecSpecs.in(warehouseId), BoxSpecSpecs.keyword(keyword)));
        List<BoxSpec> filtered = matched.stream()
                .filter(spec -> active == null || active == Boolean.TRUE.equals(spec.getIsActive()))
                .filter(spec -> custom == null || custom == Boolean.TRUE.equals(spec.getIsCustom()))
                .sorted(BoxSpecOrdering.of(pageable.getSort()))
                .toList();

        long activeCount = matched.stream().filter(spec -> Boolean.TRUE.equals(spec.getIsActive())).count();
        long customCount = matched.stream().filter(spec -> Boolean.TRUE.equals(spec.getIsCustom())).count();
        Map<String, Long> counts = Map.of("ALL", (long) matched.size(), "ACTIVE", activeCount,
                "INACTIVE", matched.size() - activeCount, "CUSTOM", customCount);

        Map<UUID, Long> linked = linkedProductCounts(warehouseId);
        PageResponse<BoxStockResponse> body = PageResponse.of(PageRequests.slice(filtered, pageable),
                spec -> BoxStockResponse.from(spec, linked.getOrDefault(spec.getId(), 0L),
                        InventoryStatus.of(spec, bufferDays), zone, usage.daily(spec.getId()), usage.dataAsOf()),
                counts);
        return BoxStockPageResponse.of(body, new BoxStockPageResponse.Summary(matched.size(),
                matched.size() - customCount, customCount, inventoryService.summarize(warehouseId)));
    }

    private Map<UUID, Long> linkedProductCounts(UUID warehouseId) {
        return productRepository.countActiveProductsByBox(warehouseId).stream().collect(Collectors.toMap(
                ProductRepository.BoxProductCount::getBoxSpecId, ProductRepository.BoxProductCount::getProductCount));
    }
}
