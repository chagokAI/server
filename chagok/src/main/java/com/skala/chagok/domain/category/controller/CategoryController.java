package com.skala.chagok.domain.category.controller;

import com.skala.chagok.domain.category.dto.CategoryResponse;
import com.skala.chagok.domain.category.service.CategoryService;
import com.skala.chagok.global.response.ApiResponse;
import com.skala.chagok.global.response.ApiResponses;

import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/warehouses/{warehouseId}/categories")
public class CategoryController {

    private final CategoryService categoryService;
    private final ApiResponses apiResponses;

    /** COMMON-API-01: 카테고리 필터·드롭다운용. 개수가 적어 페이지 없이 전체를 이름순으로 준다. */
    @GetMapping
    public ApiResponse<List<CategoryResponse>> getCategories(@PathVariable UUID warehouseId) {
        return apiResponses.of(categoryService.getCategories(warehouseId));
    }
}
