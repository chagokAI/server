package com.skala.chagok.domain.box.repository;

import com.skala.chagok.domain.box.entity.BoxSpec;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BoxSpecRepository extends JpaRepository<BoxSpec, UUID>, JpaSpecificationExecutor<BoxSpec> {

    // 응답에 공급업체 이름이 포함되므로 함께 조인해 N+1을 막는다.
    // 화면 순서(S → M → L) 정렬은 SQL로 표현하기 어려워 목록 전체를 받아 BoxSpecOrdering으로 정렬한다.
    @Override
    @EntityGraph(attributePaths = "supplier")
    List<BoxSpec> findAll(Specification<BoxSpec> spec);

    @EntityGraph(attributePaths = "supplier")
    Optional<BoxSpec> findByIdAndWarehouseId(UUID id, UUID warehouseId);

    /** 재고 관리 화면용. 리드타임으로 상태를 판정하므로 공급업체를 함께 읽는다. */
    @EntityGraph(attributePaths = "supplier")
    List<BoxSpec> findByWarehouseIdAndIsActiveTrue(UUID warehouseId);

    boolean existsByWarehouseIdAndCode(UUID warehouseId, String code);

    boolean existsByWarehouseIdAndCodeAndIdNot(UUID warehouseId, String code, UUID id);
}
