package com.skala.chagok.domain.inventory.service;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.inventory.entity.InventoryMovement;
import com.skala.chagok.domain.inventory.repository.InventoryMovementRepository;
import com.skala.chagok.domain.notification.service.DomainNotificationService;
import com.skala.chagok.domain.product.entity.MatchStatus;
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

/** 센터·이벤트를 잠근 결과 반영 트랜잭션 안에서 실제 재고의 박스 1개를 사용한다. */
@Service @RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryPackingService {
    private final ProductMatchingService matching;
    private final BoxSpecRepository boxes;
    private final InventoryMovementRepository movements;
    private final DomainNotificationService notices;
    private final ChagokProperties properties;

    public enum WaitingReason { NO_AVAILABLE_BOX, REVIEW_REQUIRED }
    public record Result(BoxSpec box, BigDecimal fillRatePct, WaitingReason waitingReason) {
        public boolean packed() { return box != null; }
        static Result waiting(WaitingReason reason) { return new Result(null, null, reason); }
    }

    /** 호출자가 센터와 미완료 이벤트를 잠그고 결과 확정까지 같은 트랜잭션을 유지한다. */
    public Result pack(Product product, String eventId) {
        var candidates = matching.packingCandidates(product);
        if (candidates.isEmpty()) return Result.waiting(WaitingReason.NO_AVAILABLE_BOX);
        var best = candidates.getFirst();
        String source = ProductMatchingService.SOURCE_TILTED.equals(best.source()) ? best.source()
                : "MANUAL".equals(product.getMatchSource()) ? "MANUAL" : best.source();
        if (Product.matchStatusFor(source, best.fillRatePct()) != MatchStatus.MATCHED)
            return Result.waiting(WaitingReason.REVIEW_REQUIRED);

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
                .memo("DL packing event=" + eventId).build());
        notices.inventory(box, wasShort, !Boolean.FALSE.equals(box.getAlertEnabled()));
        return new Result(box, best.fillRatePct(), null);
    }
}
