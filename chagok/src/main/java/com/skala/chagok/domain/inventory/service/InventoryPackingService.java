package com.skala.chagok.domain.inventory.service;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.inventory.entity.InventoryMovement;
import com.skala.chagok.domain.inventory.repository.InventoryMovementRepository;
import com.skala.chagok.domain.notification.service.DomainNotificationService;
import com.skala.chagok.domain.product.entity.Product;
import com.skala.chagok.domain.product.service.ProductMatchingService;
import com.skala.chagok.global.config.ChagokProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 센터·이벤트를 잠근 결과 반영 트랜잭션 안에서 실제 재고의 박스 1개를 사용한다.
 *
 * <p>매칭 상태(검토 필요 등)는 사람과 에이전트가 나중에 박스 구성을 개선하려고 보는 라벨이라 포장 여부를 정하지 않는다.
 * 처음에는 검토 대상 후보를 대기로 돌렸으나, 충전율이 낮아도 실제 포장은 진행한다는 업무 규칙에 맞춰 그 분기를 없앴다.
 * 포장하지 못하는 경우는 재고가 있고 규격에 맞는 박스가 없을 때뿐이다.
 */
@Service @RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryPackingService {
    public static final String MOVEMENT_MEMO_PREFIX = "DL packing event=";
    private final ProductMatchingService matching;
    private final BoxSpecRepository boxes;
    private final InventoryMovementRepository movements;
    private final DomainNotificationService notices;
    private final ChagokProperties properties;

    /** box가 null이면 쓸 수 있는 박스가 없어 포장하지 못한 것이다. */
    public record Result(BoxSpec box, BigDecimal fillRatePct) {
        public boolean packed() { return box != null; }
        static Result unavailable() { return new Result(null, null); }
    }

    /** 호출자가 센터와 미완료 이벤트를 잠그고 결과 확정까지 같은 트랜잭션을 유지한다. */
    public Result pack(Product product, String eventId) {
        var candidates = matching.packingCandidates(product);
        if (candidates.isEmpty()) return Result.unavailable();
        var best = candidates.getFirst();

        UUID warehouseId = product.getWarehouse().getId();
        var box = boxes.findByIdAndWarehouseId(best.boxSpec().id(), warehouseId).orElseThrow();
        int before = box.getOnHandQty();
        var now = Instant.now();
        boolean wasShort = DomainNotificationService.shortage(box);
        UUID requestId = UUID.nameUUIDFromBytes(("dl-packing:" + eventId).getBytes(StandardCharsets.UTF_8));
        box.changeStock(before - 1, now);
        movements.saveAndFlush(InventoryMovement.builder().warehouseId(warehouseId).boxSpecId(box.getId())
                .requestId(requestId).type("CONSUMPTION").deltaQty(-1L).beforeQty(before).afterQty(before - 1)
                .occurredOn(now.atZone(properties.timezone()).toLocalDate()).createdAt(now)
                .memo(MOVEMENT_MEMO_PREFIX + eventId).build());
        notices.inventory(box, wasShort, !Boolean.FALSE.equals(box.getAlertEnabled()));
        return new Result(box, best.fillRatePct());
    }
}
