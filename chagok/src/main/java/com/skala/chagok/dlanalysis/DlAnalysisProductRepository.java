package com.skala.chagok.dlanalysis;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.skala.chagok.domain.product.entity.Product;

/**
 * DL이 식별한 바코드로 상품을 찾는다. product.code가 바코드다(DB 담당 확인).
 *
 * <p>코드에 고유 제약이 없으므로 목록으로 받아, 둘 이상이면 호출자가 임의로 고르지 않고 실패로 처리한다.
 */
interface DlAnalysisProductRepository extends Repository<Product, UUID> {

    List<Product> findByWarehouse_IdAndCodeAndIsActiveTrue(UUID warehouseId, String code);
}
