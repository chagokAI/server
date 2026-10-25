package com.skala.chagok.workflow;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.box.service.BoxSizeClassifier;
import com.skala.chagok.domain.procurement.dto.*;
import com.skala.chagok.domain.procurement.entity.*;
import com.skala.chagok.domain.procurement.repository.ProcurementProposalItemRepository;
import com.skala.chagok.domain.procurement.service.AgentProposalItemService;
import com.skala.chagok.domain.supplier.entity.Supplier;
import com.skala.chagok.domain.supplier.repository.SupplierRepository;
import com.skala.chagok.domain.warehouse.entity.Warehouse;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.BusinessException;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentProposalPlanningTests {
    final Warehouse warehouse = Warehouse.builder().id(UUID.randomUUID()).build();
    final Supplier supplier = Supplier.builder().id(UUID.randomUUID()).warehouse(warehouse).name("박스왕").build();
    final BoxSpecRepository boxes = mock(BoxSpecRepository.class);
    final SupplierRepository suppliers = mock(SupplierRepository.class);
    final ProcurementProposalItemRepository items = mock(ProcurementProposalItemRepository.class);
    final BoxSizeClassifier sizes = new BoxSizeClassifier(new ChagokProperties(ZoneId.of("Asia/Seoul"), 300,
            new ChagokProperties.Inventory(3, 14), new ChagokProperties.BoxSize(10, 50)));
    final AgentProposalItemService service = new AgentProposalItemService(boxes, suppliers, items, sizes,
            mock(com.skala.chagok.domain.product.service.ProductMatchService.class));

    Map<String, Object> summary() {
        var line = new LinkedHashMap<String, Object>();
        line.putAll(Map.of("type", "NEW_SPEC", "boxCode", "N-237호", "dims", List.of(450,320,100),
                "qty", 2000, "unitPriceKrw", 749, "currentQty", 0, "safetyQty", 382, "expectedUsage", 1635));
        return new LinkedHashMap<>(Map.of("approval", Map.of("status", "APPROVED"), "clientView", Map.of(),
                "forecast", Map.of("forecast", 8410), "lines", List.of(line),
                "newSpecs", List.of(Map.of("code", "N-237호", "name", "237호", "dims", List.of(450,320,100),
                        "price", 749, "type", "CATALOG", "ref", "boxking:424", "monthlySaving", 792274)),
                "usagePlan", List.of(Map.of("code", "N-237호", "dims", List.of(450,320,100), "unitPrice", 749, "expectedUsage", 1635),
                        Map.of("code", "1호", "dims", List.of(220,190,90), "unitPrice", 224, "expectedUsage", 6775))));
    }

    ProcurementProposal proposal(Map<String, Object> summary) {
        return ProcurementProposal.builder().id(UUID.randomUUID()).warehouse(warehouse).targetMonth("2026-11").summary(summary).build();
    }

    @Test void pendingCatalogQuantityIsVisibleAndBecomesARealPricedSupplierItemOnApply() {
        var proposal = proposal(summary());
        var visible = ProposalResponse.from(proposal, List.of());
        assertThat(visible.totalQty()).isEqualTo(2000);
        assertThat(visible.totalAmountKrw()).isEqualTo(1498000);
        assertThat(visible.items().getFirst().boxSpec().id()).isNull();
        when(items.findByProposalId(proposal.getId())).thenReturn(List.of());
        when(suppliers.findByWarehouseId(warehouse.getId())).thenReturn(List.of(supplier));
        when(boxes.findByWarehouseIdAndCode(warehouse.getId(), "N-237호")).thenReturn(Optional.empty());
        when(boxes.saveAndFlush(any())).thenAnswer(invocation -> {
            BoxSpec box = invocation.getArgument(0); ReflectionTestUtils.setField(box, "id", UUID.randomUUID()); return box;
        });
        service.materialize(proposal);
        var captured = org.mockito.ArgumentCaptor.forClass(ProcurementProposalItem.class);
        verify(items).saveAndFlush(captured.capture());
        var line = captured.getValue();
        assertThat(line.getBoxSpec().getSupplier().getId()).isEqualTo(supplier.getId());
        assertThat(line.getBoxSpec().getOnHandQty()).isZero();
        assertThat(line.getProposedQty()).isEqualTo(2000);
        assertThat(line.getUnitPriceKrw()).isEqualTo(749);
        assertThat(line.getBoxSpec().getSizeClass()).isEqualTo('M');
        assertThat(ProposalResponse.from(proposal, List.of(line)).totalQty()).isEqualTo(2000); // 중복 합산 금지
        when(items.findByProposalId(proposal.getId())).thenReturn(List.of(line));
        clearInvocations(items, boxes);
        service.materialize(proposal);
        verify(items, never()).saveAndFlush(any());
        verify(boxes, never()).saveAndFlush(any());
    }

    @Test void missingOrAmbiguousSupplierCannotSilentlyCreateAnOrderItem() {
        var proposal = proposal(summary());
        when(items.findByProposalId(proposal.getId())).thenReturn(List.of());
        when(suppliers.findByWarehouseId(warehouse.getId())).thenReturn(List.of());
        assertThatThrownBy(() -> service.materialize(proposal)).isInstanceOf(BusinessException.class).hasMessageContaining("공급사");
        verify(boxes, never()).saveAndFlush(any());
        verify(items, never()).saveAndFlush(any());
    }

    @Test void unapprovedOrMismatchedCatalogDataCannotBeMaterialized() {
        var data = summary(); data.put("approval", Map.of("status", "PENDING"));
        assertThat(ProposalResponse.from(proposal(data), List.of()).totalQty()).isZero();
        service.materialize(proposal(data));
        verify(boxes, never()).saveAndFlush(any());
        data = summary();
        AgentProposalData.rows(data.get("lines")).getFirst().put("unitPriceKrw", 1);
        var changed = proposal(data);
        assertThatThrownBy(() -> service.materialize(changed)).isInstanceOf(BusinessException.class).hasMessageContaining("카탈로그");
    }

    @Test void fullUsagePlanDrivesForecastAndCostInsteadOfOnlyTheOrderedNewBox() {
        var projection = AgentPlanProjection.from(ProposalResponse.from(proposal(summary()), List.of()), sizes);
        assertThat(projection.totalUsage()).isEqualTo(8410);
        assertThat(projection.usage()).hasSize(2);
        assertThat(projection.boxCostKrw()).isEqualTo(1635L*749 + 6775L*224);
        assertThat(projection.monthlySavingsKrw()).isEqualTo(792274);
    }

    Map<String, Object> customSummary() {
        var data = summary();
        var line = AgentProposalData.rows(data.get("lines")).getFirst();
        line.put("type", "CUSTOM_SPEC");
        line.put("boxCode", "N-맞춤 450x320x100");
        var quote = Map.of("ok", true, "supplier", "upackage", "spec", "A형 B골 무인쇄",
                "dims", List.of(450, 320, 100), "unitPrice", 749);
        data.put("newSpecs", List.of(Map.of("code", line.get("boxCode"), "name", "맞춤 450x320x100",
                "dims", line.get("dims"), "price", 749, "type", "CUSTOM", "ref", "upackage:A형 B골 무인쇄", "quote", quote)));
        return data;
    }

    @Test void customQuantityIsIncludedButBoxStaysInactiveUntilOrderApproval() {
        var proposal = proposal(customSummary());
        var customSupplier = Supplier.builder().id(UUID.randomUUID()).warehouse(warehouse).name("유팩").build();
        when(suppliers.findByWarehouseId(warehouse.getId())).thenReturn(List.of(customSupplier));
        when(boxes.saveAndFlush(any())).thenAnswer(invocation -> {
            BoxSpec box = invocation.getArgument(0); ReflectionTestUtils.setField(box, "id", UUID.randomUUID()); return box;
        });
        assertThat(ProposalResponse.from(proposal, List.of()).totalQty()).isEqualTo(2000);
        service.materialize(proposal);
        var captured = org.mockito.ArgumentCaptor.forClass(ProcurementProposalItem.class);
        verify(items).saveAndFlush(captured.capture());
        var box = captured.getValue().getBoxSpec();
        assertThat(box.getIsCustom()).isTrue();
        assertThat(box.getIsActive()).isFalse();
        assertThat(box.getOnHandQty()).isZero();
        assertThat(box.getDescription()).isEqualTo("custom:upackage:A형 B골 무인쇄");
        assertThat(service.pendingCustom(proposal, box)).isTrue();
        assertThat(service.pendingCustom(proposal(summary()), box)).isFalse();
    }

    @Test void customSupplierIsRegisteredFromValidatedQuoteWithoutGuessingLeadTime() {
        when(suppliers.findByWarehouseId(warehouse.getId())).thenReturn(List.of());
        when(suppliers.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(boxes.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service.materialize(proposal(customSummary()));
        var captured = org.mockito.ArgumentCaptor.forClass(Supplier.class);
        verify(suppliers).saveAndFlush(captured.capture());
        assertThat(captured.getValue().getName()).isEqualTo("유팩");
        assertThat(captured.getValue().getWarehouse()).isSameAs(warehouse);
        assertThat(captured.getValue().getLeadTimeDays()).isNull();
    }

    @Test void customWithoutMatchingQuoteCannotBecomeAPurchaseItem() {
        var data = customSummary();
        var candidate = new LinkedHashMap<>(AgentProposalData.rows(data.get("newSpecs")).getFirst());
        candidate.put("orderQuote", Map.of("ok", false));
        data.put("newSpecs", List.of(candidate));
        assertThatThrownBy(() -> service.materialize(proposal(data)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("견적");
        verify(boxes, never()).saveAndFlush(any());
        verify(suppliers, never()).saveAndFlush(any());
        verify(items, never()).saveAndFlush(any());
    }

    @Test void combinedSimulationSavingsAreUsedWithoutAddingOverlappingCandidates() {
        var data = summary();
        data.put("newSpecs", List.of(Map.of("monthlySaving", 100), Map.of("monthlySaving", 200)));
        data.put("simulation", Map.of("monthly_saving", 250));
        assertThat(AgentPlanProjection.from(ProposalResponse.from(proposal(data), List.of()), sizes).monthlySavingsKrw()).isEqualTo(250);
        data.remove("simulation");
        assertThat(AgentPlanProjection.from(ProposalResponse.from(proposal(data), List.of()), sizes).monthlySavingsKrw()).isNull();
    }
}
