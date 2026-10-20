package com.skala.chagok.domain.user.repository;

import com.skala.chagok.domain.user.entity.AppUser;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findFirstByOrderByEmployeeNoAsc();
    java.util.List<AppUser> findByWarehouseId(UUID warehouseId);
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "warehouse")
    java.util.List<AppUser> findByEmployeeNo(String employeeNo);
    @Override @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "warehouse")
    Optional<AppUser> findById(UUID id);
}
