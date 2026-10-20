package com.skala.chagok.domain.inventory.dto;

import com.skala.chagok.global.response.PageResponse;

import java.util.List;
import java.util.Map;

/**
 * INV-API-01 응답. 공통 목록 형식에 summary를 더한다.
 */
public record InventoryPageResponse(
        List<InventoryItemResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        Map<String, Long> counts,
        InventorySummary summary
) {

    public static InventoryPageResponse of(PageResponse<InventoryItemResponse> page, InventorySummary summary) {
        return new InventoryPageResponse(page.items(), page.page(), page.size(), page.totalElements(),
                page.totalPages(), page.counts(), summary);
    }
}
