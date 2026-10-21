package com.skala.chagok.domain.product.service;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.category.entity.Category;
import com.skala.chagok.domain.category.repository.CategoryRepository;
import com.skala.chagok.domain.product.dto.MatchOverrideRequest;
import com.skala.chagok.domain.product.dto.MatchRecalculationResponse;
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
import java.util.List;
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
    private final CategoryRepository categoryRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductMatchingService matching;

    public PageResponse<ProductMatchResponse> getProductMatches(UUID warehouseId, SearchBy searchBy, String keyword,
                                                                UUID categoryId, MatchStatus status,
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
        Product product = findProduct(warehouseId, productId);
        return ProductDetailResponse.from(product, matching.candidates(product));
    }

    /** 상품 수정 모달 저장. 상품 정보 수정과 박스 직접 지정을 한 트랜잭션에서 처리한다. */
    @Transactional
    public ProductDetailResponse updateProduct(UUID warehouseId, UUID productId, ProductUpdateRequest request) {
        warehouseRepository.lockById(warehouseId).orElseThrow(() -> BusinessException.notFound("센터"));
        Product product = findProduct(warehouseId, productId);
        checkVersion(product.getVersion(), request.expectedVersion());

        Category category = request.categoryId() == null ? null
                : categoryRepository.findByIdAndWarehouseId(request.categoryId(), warehouseId)
                        .orElseThrow(() -> BusinessException.invalidField("categoryId", "존재하지 않는 카테고리입니다."));
        if (request.barcode() != null && productRepository.existsByWarehouseIdAndBarcodeAndIdNot(warehouseId, request.barcode(), productId)) {
            throw BusinessException.invalidField("barcode", "이미 등록된 바코드입니다.");
        }
        product.updateIdentity(request.barcode(), request.weightG());
        boolean dimensionsChanged = request.widthMm() != null || request.depthMm() != null || request.heightMm() != null;
        product.update(request.name(), category,
                request.widthMm(), request.depthMm(), request.heightMm(), request.imageUrl());

        // 박스 변경과 치수 변경을 구분한다. 같은 박스여도 치수가 바뀌면 다시 검증한다.
        BoxSpec current = product.getMatchedBoxSpec();
        boolean boxChanged = request.boxSpecId() != null
                && (current == null || !current.getId().equals(request.boxSpecId()));
        if (boxChanged) {
            if (request.matchOverrideReason() == null || request.matchOverrideReason().isBlank()) {
                throw BusinessException.invalidField("matchOverrideReason", "박스를 직접 지정할 때는 사유가 필요합니다.");
            }
            // 사이즈를 함께 고친 경우에도 위에서 먼저 반영했으므로 새 사이즈 기준으로 계산된다.
            applyOverride(product, warehouseId, request.boxSpecId(), request.matchOverrideReason());
        } else if (dimensionsChanged) {
            // 직접 지정이 아니면 새 치수 기준 1순위 박스를 자동 적용하고, 직접 지정이면 현재 박스만 다시 검증한다.
            matching.applyBestCandidate(product);
        }

        // @Version은 flush 시점에 올라가므로, 응답에 새 버전을 담으려면 먼저 flush한다.
        productRepository.flush();
        return ProductDetailResponse.from(product, matching.candidates(product));
    }

    /**
     * 매칭 박스가 없는 활성 상품에 치수 기준 1순위 박스를 일괄 적용한다. 상품이 DB에 직접 들어온 경우를 채우는 용도다.
     * 이미 매칭된 상품(직접 지정 포함)은 건드리지 않으므로 여러 번 호출해도 결과가 같다.
     */
    @Transactional
    public MatchRecalculationResponse recalculateUnmatched(UUID warehouseId) {
        warehouseRepository.lockById(warehouseId).orElseThrow(() -> BusinessException.notFound("센터"));
        List<Product> targets = productRepository.findByWarehouseIdAndIsActiveTrueAndMatchedBoxSpecIsNull(warehouseId);
        List<BoxSpec> activeBoxes = boxSpecRepository.findByWarehouseIdAndIsActiveTrue(warehouseId);
        targets.forEach(product -> matching.applyBestCandidate(product, activeBoxes));
        productRepository.flush();

        return new MatchRecalculationResponse(targets.size(),
                countStatus(targets, MatchStatus.MATCHED),
                countStatus(targets, MatchStatus.REVIEW),
                countStatus(targets, MatchStatus.UNMATCHED));
    }

    private static int countStatus(List<Product> products, MatchStatus status) {
        return (int) products.stream().filter(product -> product.getMatchStatus() == status).count();
    }

    @Transactional
    public ProductMatchResponse overrideMatch(UUID warehouseId, UUID productId, MatchOverrideRequest request) {
        warehouseRepository.lockById(warehouseId).orElseThrow(() -> BusinessException.notFound("센터"));
        Product product = findProduct(warehouseId, productId);
        checkVersion(product.getVersion(), request.expectedVersion());

        applyOverride(product, warehouseId, request.boxSpecId(), request.matchOverrideReason());
        productRepository.flush();
        return ProductMatchResponse.from(product);
    }

    @Transactional
    public void deactivateProduct(UUID warehouseId, UUID productId, Integer expectedVersion) {
        Product product = findProduct(warehouseId, productId);
        checkVersion(product.getVersion(), expectedVersion);
        product.deactivate();
    }

    /** 박스를 직접 지정한다. 상품이 박스에 들어가야 하며, 포장공간비율은 부피비로 계산한다. */
    private void applyOverride(Product product, UUID warehouseId, UUID boxSpecId, String reason) {
        BoxSpec boxSpec = boxSpecRepository.findByIdAndWarehouseId(boxSpecId, warehouseId)
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

        product.overrideMatch(boxSpec, FillRateCalculator.fillRatePct(productDims, boxDims), reason.trim());
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
