package com.skala.chagok.domain.box.dto;

import com.skala.chagok.global.response.PageResponse;
import java.util.List;
import java.util.Map;

public record BoxSpecPageResponse(List<BoxSpecResponse> items, int page, int size, long totalElements,
                                  int totalPages, Map<String, Long> counts, Summary summary) {
    public record Summary(long totalSpecCount, long standardSpecCount, long customSpecCount) {}

    public static BoxSpecPageResponse of(PageResponse<BoxSpecResponse> page, Summary summary) {
        return new BoxSpecPageResponse(page.items(), page.page(), page.size(), page.totalElements(),
                page.totalPages(), page.counts(), summary);
    }
}
