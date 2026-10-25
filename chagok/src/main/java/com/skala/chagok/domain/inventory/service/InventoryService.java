package com.skala.chagok.domain.inventory.service;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.inventory.dto.InventoryItemResponse;
import com.skala.chagok.domain.inventory.dto.InventoryPolicyUpdateRequest;
import com.skala.chagok.domain.inventory.dto.InventoryStatus;
import com.skala.chagok.domain.inventory.dto.InventorySummary;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.error.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 규격별 재고 정책과 센터 재고 요약. 재고는 box_spec의 재고 컬럼을 그대로 읽는다.
 *
 * <p>요약은 검색어·상태 필터와 관계없이 센터의 활성 규격 전체 기준이다. 목록은 박스 재고/규격 관리 화면이
 * 규격과 함께 내려주므로({@code GET box-stocks}) 여기서는 요약만 계산한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService {

    private final BoxSpecRepository boxSpecRepository;
    private final WarehouseRepository warehouseRepository;
    private final InventoryUsageReader usages;
    private final ChagokProperties properties;
    private final com.skala.chagok.domain.notification.service.DomainNotificationService notices;

    /** 센터의 활성 규격 전체 기준 요약. 대시보드와 박스 재고/규격 목록이 같은 값을 쓴다. */
    public InventorySummary summarize(UUID warehouseId) {
        requireWarehouse(warehouseId);
        int bufferDays = properties.inventory().cautionBufferDays();
        List<BoxSpec> specs = boxSpecRepository.findByWarehouseIdAndIsActiveTrue(warehouseId);
        return summarize(specs, bufferDays, usages.read(warehouseId));
    }

    @Transactional
    public InventoryItemResponse updatePolicy(UUID warehouseId, UUID inventoryId, InventoryPolicyUpdateRequest request) {
        warehouseRepository.lockById(warehouseId).orElseThrow(() -> BusinessException.notFound("센터"));
        BoxSpec boxSpec = boxSpecRepository.findByIdAndWarehouseId(inventoryId, warehouseId)
                .orElseThrow(() -> BusinessException.notFound("박스 재고"));
        if (!Objects.equals(boxSpec.getVersion(), request.expectedVersion())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }

        if (request.safetyQty() == null && request.safetyDays() == null && request.alertEnabled() == null && request.targetQty() == null)
            throw BusinessException.invalidField("safetyQty", "변경할 정책을 입력하세요.");
        InventoryUsage usage = usages.read(warehouseId);
        BigDecimal daily = usage.daily(inventoryId);
        Integer qty = request.safetyQty();
        if (request.safetyDays() != null && qty == null) {
            if (daily == null) throw new BusinessException(ErrorCode.INVALID_STATE, "일수 기준 계산을 위한 이번 달 참고 수량이 없습니다. safetyQty를 직접 입력하세요.");
            try {
                qty = BigDecimal.valueOf(usage.monthly().get(inventoryId)).multiply(BigDecimal.valueOf(request.safetyDays()))
                        .divide(BigDecimal.valueOf(usage.month().lengthOfMonth()), 0, RoundingMode.CEILING).intValueExact();
            } catch (ArithmeticException e) { throw BusinessException.invalidField("safetyDays", "안전 재고 수량 범위를 초과했습니다."); }
        }
        boolean wasShort = com.skala.chagok.domain.notification.service.DomainNotificationService.shortage(boxSpec);
        boolean wasEnabled = !Boolean.FALSE.equals(boxSpec.getAlertEnabled());
        boxSpec.updatePolicy(qty, request.safetyDays(), request.alertEnabled(), request.targetQty());
        notices.inventory(boxSpec, wasShort, wasEnabled);
        // @Version은 flush 시점에 올라가므로, 응답에 새 버전을 담으려면 먼저 flush한다.
        boxSpecRepository.flush();
        InventoryStatus status = InventoryStatus.of(boxSpec, properties.inventory().cautionBufferDays());
        return InventoryItemResponse.from(boxSpec, status, properties.timezone(), daily, usage.dataAsOf());
    }

    private InventorySummary summarize(List<BoxSpec> specs, int bufferDays, InventoryUsage usage) {
        Long totalOnHand = specs.stream().anyMatch(spec -> spec.getOnHandQty() == null) ? null : specs.stream()
                .mapToLong(spec -> spec.getOnHandQty() == null ? 0 : spec.getOnHandQty())
                .sum();
        List<String> shortageCodes = specs.stream()
                .filter(spec -> InventoryStatus.of(spec, bufferDays) == InventoryStatus.SHORTAGE)
                .map(BoxSpec::getCode)
                .sorted(Comparator.nullsLast(Comparator.naturalOrder()))
                .toList();

        OptionalDouble avgDays = specs.stream()
                .map(BoxSpec::getDaysToStockout)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average();

        Instant lastUpdated = specs.stream()
                .map(BoxSpec::getStockUpdatedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        return new InventorySummary(
                totalOnHand,
                specs.size(),
                shortageCodes.size(),
                shortageCodes,
                usage.weekly(),
                null,
                avgDays.isPresent() ? BigDecimal.valueOf(avgDays.getAsDouble()).setScale(1, RoundingMode.HALF_UP) : null,
                properties.inventory().targetDaysToStockout(),
                lastUpdated == null ? null : lastUpdated.atZone(properties.timezone()).toOffsetDateTime(),
                usage.source(),
                (int) specs.stream().filter(spec -> InventoryStatus.of(spec, bufferDays) == InventoryStatus.UNKNOWN).count()
        );
    }

    private void requireWarehouse(UUID warehouseId) {
        if (!warehouseRepository.existsById(warehouseId)) {
            throw BusinessException.notFound("센터");
        }
    }
}
