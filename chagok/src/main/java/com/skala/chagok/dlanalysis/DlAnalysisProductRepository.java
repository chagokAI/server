package com.skala.chagok.dlanalysis;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.skala.chagok.domain.product.entity.Product;

/**
 * DL 바코드를 별도 barcode로 찾는다. 기존 barcode가 없는 상품만 code를 호환 조회한다.
 *
 * <p>코드에 고유 제약이 없으므로 목록으로 받아, 둘 이상이면 호출자가 임의로 고르지 않고 실패로 처리한다.
 */
interface DlAnalysisProductRepository extends Repository<Product, UUID> {

    @org.springframework.data.jpa.repository.Query("select p from Product p where p.warehouse.id = :warehouseId and p.isActive = true "
            + "and (p.barcode = :code or (p.barcode is null and p.code = :code))")
    List<Product> findByWarehouse_IdAndCodeAndIsActiveTrue(UUID warehouseId, String code);
}
