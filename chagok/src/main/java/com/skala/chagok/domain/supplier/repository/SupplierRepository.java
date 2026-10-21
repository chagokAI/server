package com.skala.chagok.domain.supplier.repository;

import com.skala.chagok.domain.supplier.entity.Supplier;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    Optional<Supplier> findByIdAndWarehouseId(UUID id, UUID warehouseId);

    List<Supplier> findByWarehouseId(UUID warehouseId);

    /** Containing은 Spring Data가 LIKE 와일드카드를 이스케이프한다. */
    List<Supplier> findByWarehouseIdAndNameContainingIgnoreCase(UUID warehouseId, String keyword);
}
