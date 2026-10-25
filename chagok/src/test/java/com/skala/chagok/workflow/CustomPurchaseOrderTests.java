package com.skala.chagok.workflow;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.inventory.repository.InventoryMovementRepository;
import com.skala.chagok.domain.notification.service.DomainNotificationService;
import com.skala.chagok.domain.procurement.entity.*;
import com.skala.chagok.domain.procurement.repository.*;
import com.skala.chagok.domain.procurement.service.AgentProposalItemService;
import com.skala.chagok.domain.purchase.dto.request.*;
import com.skala.chagok.domain.purchase.entity.*;
import com.skala.chagok.domain.purchase.repository.*;
import com.skala.chagok.domain.purchase.service.PurchaseOrderWorkflowService;
import com.skala.chagok.domain.supplier.entity.Supplier;
import com.skala.chagok.domain.user.entity.AppUser;
import com.skala.chagok.domain.warehouse.entity.Warehouse;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import com.skala.chagok.global.auth.CurrentUserProvider;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.BusinessException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomPurchaseOrderTests {
    final Warehouse warehouse = Warehouse.builder().id(UUID.randomUUID()).build();
    final Supplier supplier = Supplier.builder().id(UUID.randomUUID()).warehouse(warehouse).name("유팩").build();
    final ProcurementProposal proposal = ProcurementProposal.builder().id(UUID.randomUUID()).warehouse(warehouse).build();
    final BoxSpec custom = BoxSpec.builder().id(UUID.randomUUID()).warehouse(warehouse).supplier(supplier)
            .code("N-맞춤").isCustom(true).isActive(false).onHandQty(0).build();
    final PurchaseOrder order = PurchaseOrder.builder().id(UUID.randomUUID()).warehouse(warehouse).supplier(supplier)
            .proposal(proposal).version(0).status(PurchaseOrderStatus.PENDING)
            .requestedDeliveryDate(LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(7)).build();
    final PurchaseOrderItem customLine = PurchaseOrderItem.builder().purchaseOrder(order).boxSpec(custom).qty(10).unitPriceKrw(300).build();
    final WarehouseRepository warehouses = mock(WarehouseRepository.class);
    final ProcurementProposalRepository proposals = mock(ProcurementProposalRepository.class);
    final ProcurementProposalItemRepository proposalItems = mock(ProcurementProposalItemRepository.class);
    final AgentProposalItemService agentItems = mock(AgentProposalItemService.class);
    final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    final PurchaseOrderItemRepository items = mock(PurchaseOrderItemRepository.class);
    final BoxSpecRepository boxes = mock(BoxSpecRepository.class);
    final CurrentUserProvider users = mock(CurrentUserProvider.class);
    final PurchaseOrderWorkflowService service = new PurchaseOrderWorkflowService(warehouses, proposals, proposalItems,
            agentItems, orders, items, boxes, mock(InventoryMovementRepository.class), users,
            new ChagokProperties(ZoneId.of("Asia/Seoul"), 300, new ChagokProperties.Inventory(3, 14),
                    new ChagokProperties.BoxSize(10, 50)), mock(DomainNotificationService.class),
            mock(com.skala.chagok.domain.product.service.ProductMatchService.class));

    @BeforeEach void prepare() {
        when(warehouses.lockById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(orders.findByIdAndWarehouseId(order.getId(), warehouse.getId())).thenReturn(Optional.of(order));
        when(items.findByPurchaseOrderIdIn(any())).thenReturn(List.of(customLine));
        when(boxes.findByIdAndWarehouseId(custom.getId(), warehouse.getId())).thenReturn(Optional.of(custom));
        when(agentItems.pendingCustom(proposal, custom)).thenReturn(true);
        when(users.getCurrentUser()).thenReturn(AppUser.builder().warehouse(warehouse).build());
    }

    @Test void positiveCustomOrderApprovalActivatesWithoutAddingStock() {
        service.update(warehouse.getId(), order.getId(), new PurchaseOrderUpdateRequest(0, null, PurchaseOrderStatus.APPROVED, null, null));
        assertThat(custom.getIsActive()).isTrue();
        assertThat(custom.getOnHandQty()).isZero();
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.APPROVED);
    }

    @Test void removedCustomLineStaysInactiveWhenOtherItemsAreApproved() {
        customLine.changeQuantity(0);
        var normal = BoxSpec.builder().id(UUID.randomUUID()).warehouse(warehouse).supplier(supplier).code("기존")
                .isActive(true).build();
        var normalLine = PurchaseOrderItem.builder().purchaseOrder(order).boxSpec(normal).qty(5).unitPriceKrw(100).build();
        when(items.findByPurchaseOrderIdIn(any())).thenReturn(List.of(customLine, normalLine));
        when(boxes.findByIdAndWarehouseId(normal.getId(), warehouse.getId())).thenReturn(Optional.of(normal));
        service.update(warehouse.getId(), order.getId(), new PurchaseOrderUpdateRequest(0, null, PurchaseOrderStatus.APPROVED, null, null));
        assertThat(custom.getIsActive()).isFalse();
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.APPROVED);
    }

    @Test void rejectionDoesNotActivateCustomSpec() {
        service.update(warehouse.getId(), order.getId(), new PurchaseOrderUpdateRequest(0, null, PurchaseOrderStatus.REJECTED, "맞춤 제외", null));
        assertThat(custom.getIsActive()).isFalse();
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.REJECTED);
    }

    @Test void unrelatedInactiveSpecCannotBeActivatedByAnOrder() {
        when(agentItems.pendingCustom(proposal, custom)).thenReturn(false);
        assertThatThrownBy(() -> service.update(warehouse.getId(), order.getId(),
                new PurchaseOrderUpdateRequest(0, null, PurchaseOrderStatus.APPROVED, null, null)))
                .isInstanceOf(BusinessException.class);
        assertThat(custom.getIsActive()).isFalse();
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.PENDING);
    }

    @Test void oldDraftReceivesMissingCustomItemOnlyOnce() {
        var normal = BoxSpec.builder().id(UUID.randomUUID()).warehouse(warehouse).supplier(supplier).code("기존").isActive(true).build();
        var normalLine = PurchaseOrderItem.builder().purchaseOrder(order).boxSpec(normal).qty(5).unitPriceKrw(100).build();
        var saved = new ArrayList<>(List.of(normalLine));
        when(proposals.findByIdAndWarehouseId(proposal.getId(), warehouse.getId())).thenReturn(Optional.of(proposal));
        when(orders.findByProposalIdOrderByPoNoAsc(proposal.getId())).thenReturn(List.of(order));
        when(items.findByPurchaseOrderIdIn(any())).thenAnswer(invocation -> List.copyOf(saved));
        when(proposalItems.findByProposalId(proposal.getId())).thenReturn(List.of(
                ProcurementProposalItem.builder().boxSpec(normal).proposedQty(5).unitPriceKrw(100).build(),
                ProcurementProposalItem.builder().boxSpec(custom).proposedQty(10).unitPriceKrw(300).build()));
        when(items.saveAllAndFlush(any())).thenAnswer(invocation -> {
            List<PurchaseOrderItem> added = invocation.getArgument(0); saved.addAll(added); return added;
        });
        service.fromProposal(warehouse.getId(), new DraftFromProposalRequest(proposal.getId(), null));
        service.fromProposal(warehouse.getId(), new DraftFromProposalRequest(proposal.getId(), null));
        assertThat(saved).hasSize(2);
        assertThat(custom.getIsActive()).isFalse();
        verify(items, times(1)).saveAllAndFlush(any());
        verify(orders, never()).saveAndFlush(any());
    }
}
