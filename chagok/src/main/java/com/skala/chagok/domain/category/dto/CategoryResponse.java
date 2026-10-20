package com.skala.chagok.domain.category.dto;

import com.skala.chagok.domain.category.entity.Category;

import java.util.UUID;

public record CategoryResponse(UUID id, String name) {

    public static CategoryResponse from(Category category) {
        return category == null ? null : new CategoryResponse(category.getId(), category.getName());
    }
}
