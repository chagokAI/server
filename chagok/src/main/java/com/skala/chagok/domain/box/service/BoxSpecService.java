package com.skala.chagok.domain.box.service;

import com.skala.chagok.domain.box.dto.BoxSpecCreateRequest;
import com.skala.chagok.domain.box.dto.BoxSpecResponse;
import com.skala.chagok.domain.box.dto.BoxSpecPageResponse;
import com.skala.chagok.domain.box.dto.BoxSpecUpdateRequest;
import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.box.repository.BoxSpecSpecs;
import com.skala.chagok.domain.event.repository.ItemEventRepository;
import com.skala.chagok.domain.procurement.repository.ProcurementProposalItemRepository;
import com.skala.chagok.domain.product.repository.ProductRepository;
import com.skala.chagok.domain.purchase.entity.PurchaseOrderStatus;
import com.skala.chagok.domain.purchase.repository.PurchaseOrderItemRepository;
import com.skala.chagok.domain.supplier.entity.Supplier;
import com.skala.chagok.domain.supplier.repository.SupplierRepository;
import com.skala.chagok.domain.warehouse.entity.Warehouse;
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
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoxSpecService {

    private static final Map<String, String> SORTS = Map.of("boxCode", "code");
    private static final String DEFAULT_SORT = "boxCode,asc";
    /** 아직 입고가 끝나지 않은 발주 상태. 삭제 불가 모달의 "진행 중 발주"에 쓴다. */
    private static final List<PurchaseOrderStatus> OPEN_PURCHASE_ORDER_STATUSES = List.of(
            PurchaseOrderStatus.PENDING, PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.IN_TRANSIT);

    private final BoxSpecRepository boxSpecRepository;
    private final WarehouseRepository warehouseRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final ProcurementProposalItemRepository procurementProposalItemRepository;
    private final ItemEventRepository itemEventRepository;
    private final com.skala.chagok.domain.inventory.repository.InventoryMovementRepository movements;
    private final com.skala.chagok.domain.product.service.ProductMatchingService matching;
    private final BoxSizeClassifier sizeClassifier;

    public BoxSpecPageResponse getBoxSpecs(UUID warehouseId, String keyword, Boolean active,
                                                     Boolean custom, int page, int size, String sort) {
        requireWarehouse(warehouseId);
        Pageable pageable = PageRequests.of(page, size, sort, SORTS, DEFAULT_SORT);

        Specification<BoxSpec> spec = Specs.allOf(
                BoxSpecSpecs.in(warehouseId),
                BoxSpecSpecs.keyword(keyword),
                BoxSpecSpecs.active(active),
                BoxSpecSpecs.custom(custom));
        // 센터당 규격은 수십 개 수준이라 전체를 읽어 메모리에서 정렬·페이지 처리한다.
        List<BoxSpec> sorted = boxSpecRepository.findAll(spec).stream()
                .sorted(BoxSpecOrdering.of(pageable.getSort()))
                .toList();
        Map<UUID, Long> linked = linkedProductCounts(warehouseId);
        Specification<BoxSpec> common = Specs.allOf(BoxSpecSpecs.in(warehouseId), BoxSpecSpecs.keyword(keyword));
        List<BoxSpec> all = boxSpecRepository.findAll(common);
        long activeCount = all.stream().filter(s -> Boolean.TRUE.equals(s.getIsActive())).count();
        long customCount = all.stream().filter(s -> Boolean.TRUE.equals(s.getIsCustom())).count();
        Map<String, Long> counts = Map.of("ALL", (long) all.size(), "ACTIVE", activeCount,
                "INACTIVE", all.size() - activeCount, "CUSTOM", customCount);
        PageResponse<BoxSpecResponse> body = PageResponse.of(PageRequests.slice(sorted, pageable),
                box -> BoxSpecResponse.from(box, linked.getOrDefault(box.getId(), 0L)), counts);
        return BoxSpecPageResponse.of(body,
                new BoxSpecPageResponse.Summary(all.size(), all.size() - customCount, customCount));
    }

    public BoxSpecResponse getBoxSpec(UUID warehouseId, UUID boxSpecId) {
        return BoxSpecResponse.from(findBoxSpec(warehouseId, boxSpecId),
                linkedProductCounts(warehouseId).getOrDefault(boxSpecId, 0L));
    }

    private Map<UUID, Long> linkedProductCounts(UUID warehouseId) {
        return productRepository.countActiveProductsByBox(warehouseId).stream().collect(Collectors.toMap(
                ProductRepository.BoxProductCount::getBoxSpecId, ProductRepository.BoxProductCount::getProductCount));
    }

    @Transactional
    public BoxSpecResponse createBoxSpec(UUID warehouseId, BoxSpecCreateRequest request) {
        Warehouse warehouse = warehouseRepository.lockById(warehouseId)
                .orElseThrow(() -> BusinessException.notFound("센터"));
        // ERD에 유니크 제약이 없어 센터 내 박스 코드 중복을 애플리케이션에서 막는다.
        if (boxSpecRepository.existsByWarehouseIdAndCode(warehouseId, request.code())) {
            throw BusinessException.invalidField("code", "이미 사용 중인 박스 코드입니다.");
        }

        BoxSpec boxSpec = BoxSpec.builder()
                .warehouse(warehouse)
                .code(request.code())
                .name(request.name())
                .sizeClass(sizeClassifier.classify(
                        request.innerWidthMm(), request.innerDepthMm(), request.innerHeightMm()))
                .innerWidthMm(request.innerWidthMm())
                .innerDepthMm(request.innerDepthMm())
                .innerHeightMm(request.innerHeightMm())
                .material(request.material().name())
                .supplier(findSupplier(warehouseId, request.supplierId()))
                .unitPriceKrw(request.unitPriceKrw())
                .isCustom(request.isCustom())
                .isActive(request.isActive() == null || request.isActive())
                .onHandQty(request.initialOnHandQty() == null ? 0 : request.initialOnHandQty())
                .safetyQty(request.safetyQty() == null ? 0 : request.safetyQty())
                .targetQty(request.targetQty())
                .alertEnabled(true)
                .stockUpdatedAt(java.time.Instant.now())
                .description(request.description())
                .build();
        return BoxSpecResponse.from(boxSpecRepository.saveAndFlush(boxSpec));
    }

    @Transactional
    public BoxSpecResponse updateBoxSpec(UUID warehouseId, UUID boxSpecId, BoxSpecUpdateRequest request) {
        warehouseRepository.lockById(warehouseId).orElseThrow(() -> BusinessException.notFound("센터"));
        BoxSpec boxSpec = findBoxSpec(warehouseId, boxSpecId);
        checkVersion(boxSpec.getVersion(), request.expectedVersion());
        if (request.code() != null
                && boxSpecRepository.existsByWarehouseIdAndCodeAndIdNot(warehouseId, request.code(), boxSpecId)) {
            throw BusinessException.invalidField("code", "이미 사용 중인 박스 코드입니다.");
        }

        boolean dimensionsChanged = request.innerWidthMm() != null || request.innerDepthMm() != null
                || request.innerHeightMm() != null;
        Character sizeClass = dimensionsChanged ? classifyOrNull(
                orElse(request.innerWidthMm(), boxSpec.getInnerWidthMm()),
                orElse(request.innerDepthMm(), boxSpec.getInnerDepthMm()),
                orElse(request.innerHeightMm(), boxSpec.getInnerHeightMm())) : null;

        boxSpec.update(
                request.code(),
                request.name(),
                sizeClass,
                request.innerWidthMm(),
                request.innerDepthMm(),
                request.innerHeightMm(),
                request.material() == null ? null : request.material().name(),
                findSupplier(warehouseId, request.supplierId()),
                request.unitPriceKrw(),
                request.isCustom(),
                request.isActive(),
                request.description());
        if (dimensionsChanged || request.isActive() != null) {
            productRepository.findByMatchedBoxSpecIdAndIsActiveTrue(boxSpecId).forEach(matching::refreshCurrent);
            productRepository.flush();
        }
        boxSpecRepository.flush();
        return BoxSpecResponse.from(boxSpec, linkedProductCounts(warehouseId).getOrDefault(boxSpecId, 0L));
    }

    /**
     * 미사용(is_active=false)이고, 상품 매칭·발주·제안·이벤트·입고/실사 이력에서 참조하지 않고, 재고가 없을 때만 삭제한다.
     * 상품과 달리 하드 삭제다. 막히면 409와 함께 삭제 불가 모달(06-2)에 보여줄 사유를 details로 내려준다.
     */
    @Transactional
    public void deleteBoxSpec(UUID warehouseId, UUID boxSpecId, Integer expectedVersion) {
        BoxSpec boxSpec = findBoxSpec(warehouseId, boxSpecId);
        checkVersion(boxSpec.getVersion(), expectedVersion);

        boolean active = Boolean.TRUE.equals(boxSpec.getIsActive());
        long linkedProductCount = linkedProductCounts(warehouseId).getOrDefault(boxSpecId, 0L);
        List<String> openPoNos = purchaseOrderItemRepository
                .findPoNosByBoxSpecIdAndStatusIn(boxSpecId, OPEN_PURCHASE_ORDER_STATUSES).stream()
                .sorted()
                .toList();
        int onHandQty = boxSpec.getOnHandQty() == null ? 0 : boxSpec.getOnHandQty();
        // 비활성 상품의 매칭·완료된 발주·제안·이벤트·입고 이력도 FK로 참조하므로 실제 삭제를 막는다.
        boolean hasHistory = productRepository.existsByMatchedBoxSpecId(boxSpecId)
                || purchaseOrderItemRepository.existsByBoxSpecId(boxSpecId)
                || procurementProposalItemRepository.existsByBoxSpecId(boxSpecId)
                || itemEventRepository.existsByBoxSpecId(boxSpecId)
                || movements.existsByBoxSpecId(boxSpecId);

        if (active || hasHistory || onHandQty > 0) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("active", active);
            details.put("linkedProductCount", linkedProductCount);
            details.put("openPurchaseOrderNos", openPoNos);
            details.put("onHandQty", onHandQty);
            details.put("hasHistory", hasHistory);
            String message = active
                    ? "사용 중인 규격은 삭제할 수 없습니다. 연결을 해제하고 미사용으로 바꾼 뒤 삭제하세요."
                    : ErrorCode.REFERENCED_SPEC.getDefaultMessage();
            throw BusinessException.withDetails(ErrorCode.REFERENCED_SPEC, message, details);
        }
        boxSpecRepository.delete(boxSpec);
    }

    private BoxSpec findBoxSpec(UUID warehouseId, UUID boxSpecId) {
        requireWarehouse(warehouseId);
        return boxSpecRepository.findByIdAndWarehouseId(boxSpecId, warehouseId)
                .orElseThrow(() -> BusinessException.notFound("박스 규격"));
    }

    private Supplier findSupplier(UUID warehouseId, UUID supplierId) {
        if (supplierId == null) {
            return null;
        }
        return supplierRepository.findByIdAndWarehouseId(supplierId, warehouseId)
                .orElseThrow(() -> BusinessException.invalidField("supplierId", "존재하지 않는 공급업체입니다."));
    }

    private void requireWarehouse(UUID warehouseId) {
        if (!warehouseRepository.existsById(warehouseId)) {
            throw BusinessException.notFound("센터");
        }
    }

    private static Integer orElse(Integer value, Integer fallback) {
        return value != null ? value : fallback;
    }

    /** 기존 규격의 치수가 비어 있으면 분류할 수 없으므로 크기 분류를 그대로 둔다(null = 변경 안 함). */
    private Character classifyOrNull(Integer widthMm, Integer depthMm, Integer heightMm) {
        if (widthMm == null || depthMm == null || heightMm == null) {
            return null;
        }
        return sizeClassifier.classify(widthMm, depthMm, heightMm);
    }

    /** ProductMatchService.checkVersion과 같은 이유로 expectedVersion을 직접 비교한다. */
    private static void checkVersion(Integer current, Integer expected) {
        if (!Objects.equals(current, expected)) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
    }
}
