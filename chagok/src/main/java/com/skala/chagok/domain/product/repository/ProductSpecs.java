package com.skala.chagok.domain.product.repository;

import com.skala.chagok.domain.product.entity.MatchStatus;
import com.skala.chagok.domain.product.entity.Product;
import com.skala.chagok.global.web.LikePatterns;

import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

/**
 * 상품 목록 검색 조건. 선택 필터가 비어 있으면 null을 반환하고 {@code Specs.allOf}에서 건너뛴다.
 */
public final class ProductSpecs {

    private ProductSpecs() {
    }

    /** 센터 소유 + 삭제되지 않은(is_active) 상품만. 모든 상품 조회의 기본 조건이다. */
    public static Specification<Product> activeIn(UUID warehouseId) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("warehouse").get("id"), warehouseId),
                cb.isTrue(root.get("isActive")));
    }

    public static Specification<Product> keyword(SearchBy searchBy, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String attribute = searchBy == SearchBy.CODE ? "code" : "name";
        String pattern = LikePatterns.containsIgnoreCase(keyword);
        return (root, query, cb) -> cb.like(cb.lower(root.get(attribute)), pattern, LikePatterns.ESCAPE);
    }

    public static Specification<Product> category(UUID categoryId) {
        if (categoryId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("category").get("id"), categoryId);
    }

    public static Specification<Product> status(MatchStatus status) {
        if (status == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("matchStatus"), status);
    }
}
