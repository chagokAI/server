package com.skala.chagok.query;

import com.skala.chagok.domain.box.dto.BoxSnapshot;
import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.dashboard.dto.MetricSeries;
import com.skala.chagok.domain.dashboard.repository.MetricQueryRepository;
import com.skala.chagok.domain.dashboard.service.ObservationQueryService;
import com.skala.chagok.domain.event.repository.ItemEventRepository;
import com.skala.chagok.domain.job.service.JobQueryService;
import com.skala.chagok.domain.procurement.dto.*;
import com.skala.chagok.domain.procurement.entity.*;
import com.skala.chagok.domain.procurement.repository.*;
import com.skala.chagok.domain.procurement.service.ProcurementQueryService;
import com.skala.chagok.domain.purchase.service.PurchaseOrderService;
import com.skala.chagok.domain.purchase.repository.PurchaseOrderRepository;
import com.skala.chagok.domain.purchase.repository.PurchaseOrderItemRepository;
import com.skala.chagok.domain.purchase.dto.request.PurchaseOrderCreateRequest;
import com.skala.chagok.domain.warehouse.entity.Warehouse;
import com.skala.chagok.global.auth.CurrentUserProvider;
import jakarta.persistence.EntityNotFoundException;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.web.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QueryServiceTests {
    private final UUID warehouseId = UUID.randomUUID();
    private final WarehouseQueryGuard guard = mock(WarehouseQueryGuard.class);
    private final ChagokProperties properties = new ChagokProperties(ZoneId.of("Asia/Seoul"), 300, new ChagokProperties.Inventory(3, 14));

    @Test void rejectsInvalidMonthsAndReversedDateRanges() {
        for (String invalid : List.of("2026-13", "26-11", "2026-1", "2026-11-01", ""))
            assertThatThrownBy(() -> QueryValues.month(invalid, YearMonth.of(2026, 11))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> QueryValues.dateRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(BusinessException.class);
    }

    @Test void missingProposalDoesNotBecomeAnEmptyZeroProposal() {
        var proposals = mock(ProcurementProposalRepository.class);
        var jobs = mock(JobQueryService.class);
        when(proposals.findFirstByWarehouseIdAndTargetMonthOrderByGeneratedAtDescIdDesc(warehouseId, "2026-11"))
                .thenReturn(Optional.empty());
        var service = new ProcurementQueryService(proposals, mock(ProcurementProposalItemRepository.class),
                mock(CostForecastRepository.class), jobs, guard, properties);
        var result = service.latestProposal(warehouseId, "2026-11");
        assertThat(result.dataStatus()).isEqualTo(LatestResult.DataStatus.NOT_GENERATED);
        assertThat(result.result()).isNull();
        verify(guard).require(warehouseId);
    }

    @Test void proposalReadsSavedDimensionsAndPriceEvenAfterCatalogChanges() {
        var current = BoxSpec.builder().id(UUID.randomUUID()).code("NEW").unitPriceKrw(9999).innerWidthMm(999).build();
        var saved = new BoxSnapshot(current.getId(), "S-1", "소형 1호", 'S', 200, 150, 100);
        var line = ProcurementProposalItem.builder().boxSpec(current).boxSnapshot(saved).unitPriceKrw(180)
                .proposedQty(3300).forecastUsageQty(4200).onHandQty(2400).safetyQty(1500).build();
        var result = ProposalResponse.from(ProcurementProposal.builder().id(UUID.randomUUID()).targetMonth("2026-11").build(), List.of(line));
        assertThat(result.totalAmountKrw()).isEqualTo(594000);
        assertThat(result.items().getFirst().boxSpec().innerWidthMm()).isEqualTo(200);
        assertThat(result.snapshotComplete()).isTrue();
    }

    @Test void legacyProposalDoesNotUseTodaysPriceToInventHistoricalAmount() {
        var box = BoxSpec.builder().id(UUID.randomUUID()).code("S-1").unitPriceKrw(9999).build();
        var result = ProposalResponse.from(ProcurementProposal.builder().build(), List.of(
                ProcurementProposalItem.builder().boxSpec(box).proposedQty(10).build()));
        assertThat(result.totalAmountKrw()).isNull();
        assertThat(result.forecastUsageQty()).isNull();
        assertThat(result.snapshotComplete()).isFalse();
    }

    @Test void zeroForecastIsAvailableAndDoesNotMeanMissing() {
        var box = BoxSpec.builder().id(UUID.randomUUID()).code("S-1").build();
        var result = ProposalResponse.from(ProcurementProposal.builder().build(), List.of(
                ProcurementProposalItem.builder().boxSpec(box).proposedQty(0).forecastUsageQty(0).unitPriceKrw(180).build()));
        assertThat(result.forecastUsageQty()).isZero();
        assertThat(result.totalAmountKrw()).isZero();
    }

    @Test void metricBucketsUseWarehouseTimezoneAndDoNotFabricateMissingAverage() {
        var repository = mock(MetricQueryRepository.class);
        Instant firstBucket = Instant.parse("2026-10-18T15:00:00Z");
        when(repository.aggregate(eq(warehouseId), eq(firstBucket), eq(firstBucket.plusSeconds(2 * 86400)),
                eq("Asia/Seoul"), eq(MetricSeries.Bucket.day), eq(MetricSeries.Metric.AVG_FILL_RATE_PCT), isNull()))
                .thenReturn(List.of(new MetricQueryRepository.Aggregate(firstBucket, new BigDecimal("81.25"), 4)));
        var service = new ObservationQueryService(mock(ItemEventRepository.class), repository, mock(BoxSpecRepository.class), guard, properties);
        var result = service.metrics(warehouseId, MetricSeries.Metric.AVG_FILL_RATE_PCT,
                LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20), MetricSeries.Bucket.day, null);
        assertThat(result.points()).hasSize(2);
        assertThat(result.points().getFirst().at().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(result.points().getFirst().sampleCount()).isEqualTo(4);
        assertThat(result.points().getLast().value()).isNull();
    }

    @Test void hourlyMetricsRejectUnboundedRangesBeforeDatabaseQuery() {
        var repository = mock(MetricQueryRepository.class);
        var service = new ObservationQueryService(mock(ItemEventRepository.class), repository, mock(BoxSpecRepository.class), guard, properties);
        assertThatThrownBy(() -> service.metrics(warehouseId, MetricSeries.Metric.OBSERVATION_COUNT,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), MetricSeries.Bucket.hour, null))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository);
    }

    @Test void creatingAnOrderCannotAttachAnotherWarehousesBoxToItsHistory() {
        UUID proposalId = UUID.randomUUID(), boxId = UUID.randomUUID();
        var proposals = mock(ProcurementProposalRepository.class);
        var orders = mock(PurchaseOrderRepository.class);
        var boxes = mock(BoxSpecRepository.class);
        when(proposals.findById(proposalId)).thenReturn(Optional.of(ProcurementProposal.builder()
                .warehouse(Warehouse.builder().id(warehouseId).build()).build()));
        when(boxes.findAllById(any())).thenReturn(List.of(BoxSpec.builder().id(boxId)
                .warehouse(Warehouse.builder().id(UUID.randomUUID()).build()).build()));
        var service = new PurchaseOrderService(orders, mock(PurchaseOrderItemRepository.class), proposals, boxes,
                mock(CurrentUserProvider.class));
        var request = new PurchaseOrderCreateRequest(proposalId,
                List.of(new PurchaseOrderCreateRequest.Item(boxId, 1, 180, 180L)), 1, 180L, LocalDate.now().plusDays(1));
        assertThatThrownBy(() -> service.create(warehouseId, request)).isInstanceOf(EntityNotFoundException.class);
        verify(orders, never()).save(any());
    }
}
