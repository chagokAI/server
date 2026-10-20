package com.skala.chagok.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * 목록 응답의 data. counts는 탭별 개수가 필요한 API에서만 채우고, 없으면 JSON에서 생략한다.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Long> counts
) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return of(page, mapper, null);
    }

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper, Map<String, Long> counts) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                counts
        );
    }
}
