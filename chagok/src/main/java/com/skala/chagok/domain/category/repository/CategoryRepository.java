package com.skala.chagok.domain.category.repository;

import com.skala.chagok.domain.category.entity.Category;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    List<Category> findByWarehouseId(UUID warehouseId);

    Optional<Category> findByIdAndWarehouseId(UUID id, UUID warehouseId);
}
