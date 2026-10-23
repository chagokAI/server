package com.skala.chagok.domain.warehouse.repository;

import com.skala.chagok.domain.warehouse.entity.Warehouse;

import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import org.springframework.data.domain.Pageable;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Warehouse w where w.id = :id")
    Optional<Warehouse> lockById(UUID id);

    @Query("select w.id from Warehouse w order by w.id")
    List<UUID> findIds(Pageable pageable);

    List<Warehouse> findAllByLatitudeIsNotNullAndLongitudeIsNotNullOrderByName();
}
