package com.skala.chagok.domain.box.dto;

import com.skala.chagok.domain.inventory.dto.InventorySummary;
import com.skala.chagok.global.response.PageResponse;

import java.util.List;
import java.util.Map;

/**
 * BOXSTOCK-API-01 응답. counts는 규격 사용 여부 탭(ALL·ACTIVE·INACTIVE·CUSTOM)이고
 * summary는 규격 개수와 센터 재고 요약(stock)을 함께 담는다.
 */
public record BoxStockPageResponse(List<BoxStockResponse> items, int page, int size, long totalElements,
                                   int totalPages, Map<String, Long> counts, Summary summary) {

    /** @param stock 검색어·탭과 관계없이 센터의 활성 규격 전체 기준 재고 요약 */
    public record Summary(long totalSpecCount, long standardSpecCount, long customSpecCount, InventorySummary stock) {
    }

    public static BoxStockPageResponse of(PageResponse<BoxStockResponse> page, Summary summary) {
        return new BoxStockPageResponse(page.items(), page.page(), page.size(), page.totalElements(),
                page.totalPages(), page.counts(), summary);
    }
}
