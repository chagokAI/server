package com.skala.chagok.domain.product.service;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.product.dto.MatchOverrideRequest;
import com.skala.chagok.domain.product.dto.ProductDetailResponse;
import com.skala.chagok.domain.product.dto.ProductMatchResponse;
import com.skala.chagok.domain.product.dto.ProductUpdateRequest;
import com.skala.chagok.domain.product.entity.MatchStatus;
import com.skala.chagok.domain.product.entity.Product;
import com.skala.chagok.domain.product.repository.ProductRepository;
import com.skala.chagok.domain.product.repository.ProductSpecs;
import com.skala.chagok.domain.product.repository.SearchBy;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.error.ErrorCode;
import com.skala.chagok.global.response.PageResponse;
import com.skala.chagok.global.web.PageRequests;
import com.skala.chagok.global.web.Specs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductMatchService {

    private static final Map<String, String> SORTS = Map.of(
            "fillRatePct", "fillRatePct",
            "name", "name");
    private static final String DEFAULT_SORT = "fillRatePct,desc";
    private static final String COUNT_ALL = "ALL";

    private final ProductRepository productRepository;
    private final BoxSpecRepository boxSpecRepository;
    private final WarehouseRepository warehouseRepository;

    public PageResponse<ProductMatchResponse> getProductMatches(UUID warehouseId, SearchBy searchBy, String keyword,
                                                                String categoryId, MatchStatus status,
                                                                int page, int size, String sort) {
        requireWarehouse(warehouseId);
        Pageable pageable = PageRequests.of(page, size, sort, SORTS, DEFAULT_SORT);

        // counts는 상태 필터만 제외한 공통 조건으로 센다.
        Specification<Product> common = Specs.allOf(
                ProductSpecs.activeIn(warehouseId),
                ProductSpecs.keyword(searchBy, keyword),
                ProductSpecs.category(categoryId));
        Specification<Product> filtered = Specs.allOf(common, ProductSpecs.status(status));

        Page<Product> result = productRepository.findAll(filtered, pageable);
        return PageResponse.of(result, ProductMatchResponse::from, countByStatus(common));
    }

    public ProductDetailResponse getProduct(UUID warehouseId, UUID productId) {
        return ProductDetailResponse.from(findProduct(warehouseId, productId));
    }

    @Transactional
    public ProductDetailResponse updateProduct(UUID warehouseId, UUID productId, ProductUpdateRequest request) {
        Product product = findProduct(warehouseId, productId);
        checkVersion(product.getVersion(), request.expectedVersion());

        product.update(request.code(), request.name(), request.category(),
                request.widthMm(), request.depthMm(), request.heightMm(), request.imageUrl());
        // @Version은 flush 시점에 올라가므로, 응답에 새 버전을 담으려면 먼저 flush한다.
        productRepository.flush();
        return ProductDetailResponse.from(product);
    }

    @Transactional
    public ProductMatchResponse overrideMatch(UUID warehouseId, UUID productId, MatchOverrideRequest request) {
        Product product = findProduct(warehouseId, productId);
        checkVersion(product.getVersion(), request.expectedVersion());

        BoxSpec boxSpec = boxSpecRepository.findByIdAndWarehouseId(request.boxSpecId(), warehouseId)
                .orElseThrow(() -> BusinessException.notFound("박스 규격"));
        if (!Boolean.TRUE.equals(boxSpec.getIsActive())) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "비활성화된 박스 규격은 지정할 수 없습니다.");
        }

        int[] productDims = dims(product.getWidthMm(), product.getDepthMm(), product.getHeightMm());
        int[] boxDims = dims(boxSpec.getInnerWidthMm(), boxSpec.getInnerDepthMm(), boxSpec.getInnerHeightMm());
        if (productDims == null || boxDims == null) {
            throw new BusinessException(ErrorCode.BOX_NOT_FIT, "상품 또는 박스 치수가 등록되지 않았습니다.");
        }
        if (!FillRateCalculator.fits(productDims, boxDims)) {
            throw new BusinessException(ErrorCode.BOX_NOT_FIT);
        }

        product.overrideMatch(boxSpec, FillRateCalculator.fillRatePct(productDims, boxDims));
        productRepository.flush();
        return ProductMatchResponse.from(product);
    }

    @Transactional
    public void deactivateProduct(UUID warehouseId, UUID productId, Integer expectedVersion) {
        Product product = findProduct(warehouseId, productId);
        checkVersion(product.getVersion(), expectedVersion);
        product.deactivate();
    }

    /** 상단 탭 숫자. 상태별로 count 쿼리를 하나씩 실행한다(상태 수가 적어 단순하게 유지). */
    private Map<String, Long> countByStatus(Specification<Product> common) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put(COUNT_ALL, productRepository.count(common));
        for (MatchStatus status : MatchStatus.values()) {
            counts.put(status.name(), productRepository.count(common.and(ProductSpecs.status(status))));
        }
        return counts;
    }

    /** 다른 센터의 상품이거나 삭제된 상품이면 404로 처리한다(센터 소유권 검증). */
    private Product findProduct(UUID warehouseId, UUID productId) {
        requireWarehouse(warehouseId);
        return productRepository.findByIdAndWarehouseIdAndIsActiveTrue(productId, warehouseId)
                .orElseThrow(() -> BusinessException.notFound("상품"));
    }

    private void requireWarehouse(UUID warehouseId) {
        if (!warehouseRepository.existsById(warehouseId)) {
            throw BusinessException.notFound("센터");
        }
    }

    /**
     * 클라이언트가 화면을 연 시점의 버전(expectedVersion)과 현재 DB 버전을 비교한다.
     * JPA @Version은 같은 트랜잭션 안의 경합만 잡으므로, 화면을 띄운 뒤 다른 사람이 수정한 경우는 여기서 막는다.
     */
    private static void checkVersion(Integer current, Integer expected) {
        if (!Objects.equals(current, expected)) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
    }

    private static int[] dims(Integer width, Integer depth, Integer height) {
        if (width == null || depth == null || height == null) {
            return null;
        }
        return new int[]{width, depth, height};
    }
}
