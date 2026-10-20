package com.skala.chagok.domain.category.service;

import com.skala.chagok.domain.category.dto.CategoryResponse;
import com.skala.chagok.domain.category.repository.CategoryRepository;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import com.skala.chagok.global.error.BusinessException;

import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final WarehouseRepository warehouseRepository;

    public List<CategoryResponse> getCategories(UUID warehouseId) {
        if (!warehouseRepository.existsById(warehouseId)) {
            throw BusinessException.notFound("센터");
        }
        return categoryRepository.findByWarehouseIdOrderByNameAscIdAsc(warehouseId).stream()
                .map(CategoryResponse::from)
                .toList();
    }
}
