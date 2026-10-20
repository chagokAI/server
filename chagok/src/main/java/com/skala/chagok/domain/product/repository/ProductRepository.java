package com.skala.chagok.domain.product.repository;

import com.skala.chagok.domain.product.entity.Product;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    // 목록에서 박스 코드·이름과 카테고리명을 함께 보여주므로 한 번에 조인해 N+1을 막는다.
    @Override
    @EntityGraph(attributePaths = {"matchedBoxSpec", "category"})
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"matchedBoxSpec", "category"})
    Optional<Product> findByIdAndWarehouseIdAndIsActiveTrue(UUID id, UUID warehouseId);

    /** 박스 규격 삭제 가능 여부 확인용. 비활성(삭제된) 상품도 FK로 참조하므로 함께 센다. */
    boolean existsByMatchedBoxSpecId(UUID boxSpecId);
}
