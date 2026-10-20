package com.skala.chagok.domain.purchase.dto.response;

import com.skala.chagok.domain.purchase.entity.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public record PurchaseOrderPageResponse(List<Row> items, int page, int size, long totalElements, int totalPages,
                                        Map<String, Long> counts, Summary summary, LocalDate from, LocalDate to) {
    public record Summary(long totalOrderCount, BigDecimal totalAmountKrw, long pendingCount,
                          BigDecimal averageDeliveryLeadTimeDays, long receivedSampleCount) {}
    public record Row(UUID id, String poNo, Instant createdAt, UUID supplierId, String supplierName,
                      List<String> boxCodes, int specCount, long totalQty, Long totalAmountKrw,
                      LocalDate deliveryDate, PurchaseOrderStatus status, Integer version) {
        public static Row from(PurchaseOrder order, List<PurchaseOrderItem> lines) {
            var items = lines.stream().map(PurchaseOrderResponse.Item::from).toList();
            return new Row(order.getId(), order.getPoNo(), order.getCreatedAt(),
                    order.getSupplier() == null ? null : order.getSupplier().getId(),
                    order.getSupplier() == null ? null : order.getSupplier().getName(),
                    items.stream().map(i -> i.boxSpec().code()).sorted(Comparator.nullsLast(Comparator.naturalOrder())).toList(),
                    items.size(), PurchaseOrderResponse.totalQty(items), PurchaseOrderResponse.totalAmount(items),
                    order.getRequestedDeliveryDate(), order.getStatus(), order.getVersion());
        }
    }
}
