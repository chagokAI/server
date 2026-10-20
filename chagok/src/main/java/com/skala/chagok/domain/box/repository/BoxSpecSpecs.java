package com.skala.chagok.domain.box.repository;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.global.web.LikePatterns;

import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

public final class BoxSpecSpecs {

    private BoxSpecSpecs() {
    }

    public static Specification<BoxSpec> in(UUID warehouseId) {
        return (root, query, cb) -> cb.equal(root.get("warehouse").get("id"), warehouseId);
    }

    /** 박스 코드 또는 이름 부분 일치 */
    public static Specification<BoxSpec> keyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String pattern = LikePatterns.containsIgnoreCase(keyword);
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("code")), pattern, LikePatterns.ESCAPE),
                cb.like(cb.lower(root.get("name")), pattern, LikePatterns.ESCAPE));
    }

    public static Specification<BoxSpec> active(Boolean active) {
        if (active == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("isActive"), active);
    }

    public static Specification<BoxSpec> custom(Boolean custom) {
        if (custom == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("isCustom"), custom);
    }
}
