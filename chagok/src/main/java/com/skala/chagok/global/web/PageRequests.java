package com.skala.chagok.global.web;

import com.skala.chagok.global.error.BusinessException;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * {@code sort=필드,asc|desc} 파라미터를 허용 목록으로 검증해 Pageable로 바꾼다.
 * 동률은 항상 id 오름차순으로 정렬한다.
 */
public final class PageRequests {

    private PageRequests() {
    }

    /**
     * @param allowedSorts API 정렬 필드명 → 엔티티 속성명
     * @param defaultSort  sort 파라미터가 없을 때 사용할 값 (예: {@code fillRatePct,desc})
     */
    public static Pageable of(int page, int size, String sort, Map<String, String> allowedSorts, String defaultSort) {
        String raw = (sort == null || sort.isBlank()) ? defaultSort : sort;
        String[] parts = raw.split(",", -1);
        if (parts.length > 2) {
            throw BusinessException.invalidField("sort", "필드,asc|desc 형식이어야 합니다.");
        }

        String property = allowedSorts.get(parts[0].trim());
        if (property == null) {
            throw BusinessException.invalidField("sort", "허용되지 않은 정렬 필드입니다: " + parts[0].trim());
        }

        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            direction = Sort.Direction.fromOptionalString(parts[1].trim())
                    .orElseThrow(() -> BusinessException.invalidField("sort", "정렬 방향은 asc 또는 desc입니다."));
        }

        Sort resolved = Sort.by(direction, property);
        if (!"id".equals(property)) {
            resolved = resolved.and(Sort.by(Sort.Direction.ASC, "id"));
        }
        return PageRequest.of(page, size, resolved);
    }

    /** 이미 정렬된 전체 목록에서 pageable 범위만 잘라 Page로 만든다(메모리 페이지 처리용). */
    public static <T> Page<T> slice(List<T> sorted, Pageable pageable) {
        int from = (int) Math.min(pageable.getOffset(), sorted.size());
        int to = Math.min(from + pageable.getPageSize(), sorted.size());
        return new PageImpl<>(sorted.subList(from, to), pageable, sorted.size());
    }
}
