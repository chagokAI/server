package com.skala.chagok.domain.supplier.repository;

import com.skala.chagok.domain.supplier.entity.Supplier;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    Optional<Supplier> findByIdAndWarehouseId(UUID id, UUID warehouseId);
}
