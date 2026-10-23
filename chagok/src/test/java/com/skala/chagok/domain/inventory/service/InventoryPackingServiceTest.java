package com.skala.chagok.domain.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.inventory.repository.InventoryMovementRepository;
import com.skala.chagok.domain.notification.service.DomainNotificationService;
import com.skala.chagok.domain.product.entity.Product;
import com.skala.chagok.domain.product.service.ProductMatchingService;
import com.skala.chagok.domain.warehouse.entity.Warehouse;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.config.MatchingProperties;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryPackingServiceTest {
    @Test void manuallyFixedBoxStillRequiresReviewWhenOnlyTiltedFitRemains() {
        var warehouse = Warehouse.builder().id(UUID.randomUUID()).build();
        var box = BoxSpec.builder().id(UUID.randomUUID()).warehouse(warehouse).code("M-9")
                .innerWidthMm(450).innerDepthMm(450).innerHeightMm(100)
                .isActive(true).onHandQty(2).build();
        var product = Product.builder().id(UUID.randomUUID()).warehouse(warehouse)
                .widthMm(520).depthMm(20).heightMm(20).isActive(true).build();
        product.overrideMatch(box, BigDecimal.ONE, "fixed assignment before dimensions changed");
        var boxes = mock(BoxSpecRepository.class);
        when(boxes.findByWarehouseIdAndIsActiveTrue(warehouse.getId())).thenReturn(List.of(box));
        when(boxes.findByIdAndWarehouseId(box.getId(), warehouse.getId())).thenReturn(Optional.of(box));
        var movements = mock(InventoryMovementRepository.class);
        var notices = mock(DomainNotificationService.class);
        var properties = mock(ChagokProperties.class);
        when(properties.timezone()).thenReturn(ZoneId.of("Asia/Seoul"));
        var matching = new ProductMatchingService(boxes,
                new MatchingProperties(new MatchingProperties.Tilted(true, 0)));

        assertThat(matching.packingCandidates(product)).singleElement()
                .satisfies(candidate -> assertThat(candidate.source()).isEqualTo("TILTED"));
        var result = new InventoryPackingService(matching, boxes, movements, notices, properties)
                .pack(product, UUID.randomUUID().toString());

        assertThat(result.waitingReason()).isEqualTo(InventoryPackingService.WaitingReason.REVIEW_REQUIRED);
        assertThat(result.packed()).isFalse();
        assertThat(box.getOnHandQty()).isEqualTo(2);
        assertThat(product.getMatchSource()).isEqualTo("MANUAL");
        verifyNoInteractions(movements, notices);
    }
}
