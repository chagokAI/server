package com.skala.chagok.global.web;

import java.util.Arrays;
import java.util.Objects;

import org.springframework.data.jpa.domain.Specification;

public final class Specs {

    private Specs() {
    }

    /** null 조건(선택 필터 미입력)은 건너뛰고 AND로 묶는다. */
    @SafeVarargs
    public static <T> Specification<T> allOf(Specification<T>... specs) {
        return Specification.allOf(Arrays.stream(specs).filter(Objects::nonNull).toList());
    }
}
