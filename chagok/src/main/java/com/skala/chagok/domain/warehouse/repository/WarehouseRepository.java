package com.skala.chagok.domain.warehouse.repository;

import com.skala.chagok.domain.warehouse.entity.Warehouse;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {
}
