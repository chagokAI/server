package com.skala.chagok.domain.box.repository;

import com.skala.chagok.domain.box.entity.BoxSpec;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BoxSpecRepository extends JpaRepository<BoxSpec, UUID>, JpaSpecificationExecutor<BoxSpec> {

    // 응답에 공급업체 이름이 포함되므로 함께 조인해 N+1을 막는다.
    @Override
    @EntityGraph(attributePaths = "supplier")
    Page<BoxSpec> findAll(Specification<BoxSpec> spec, Pageable pageable);

    @EntityGraph(attributePaths = "supplier")
    Optional<BoxSpec> findByIdAndWarehouseId(UUID id, UUID warehouseId);

    boolean existsByWarehouseIdAndCode(UUID warehouseId, String code);

    boolean existsByWarehouseIdAndCodeAndIdNot(UUID warehouseId, String code, UUID id);
}
