package com.skala.chagok.domain.purchase.dto.response;

import com.skala.chagok.domain.box.dto.BoxSnapshot;
import com.skala.chagok.domain.purchase.entity.*;
import com.skala.chagok.domain.user.entity.AppUser;
import java.time.*;
import java.util.*;

public record PurchaseOrderResponse(UUID id, String poNo, UUID proposalId, PurchaseOrderStatus status,
                                    Instant createdAt, Instant proposalGeneratedAt, Instant approvedAt, Instant receivedAt,
                                    LocalDate deliveryDate, SupplierInfo supplier, UserInfo requester, UserInfo approver,
                                    Recipient recipient, String decisionReason, Long totalQty, Long totalAmountKrw,
                                    String currency, boolean vatIncluded, Integer version, List<Item> items) {
    public record SupplierInfo(UUID id, String name, String paymentTerms, Short leadTimeDays) {}
    public record UserInfo(UUID id, String name, String department, String jobTitle) {
        static UserInfo from(AppUser user) {
            return user == null ? null : new UserInfo(user.getId(), user.getName(), user.getDepartment(), user.getJobTitle());
        }
    }
    public record Recipient(UUID warehouseId, String name, String address, String receivingHours, String receivingContact) {}
    public record Item(BoxSnapshot boxSpec, Integer qty, Integer unitPriceKrw, Long amountKrw, boolean snapshotAvailable) {
        public static Item from(PurchaseOrderItem item) {
            return new Item(item.getBoxSnapshot() == null ? BoxSnapshot.from(item.getBoxSpec()) : item.getBoxSnapshot(),
                    item.getQty(), item.getUnitPriceKrw(), item.getQty() == null || item.getUnitPriceKrw() == null ? null
                    : Math.multiplyExact(item.getQty().longValue(), item.getUnitPriceKrw().longValue()), item.getBoxSnapshot() != null);
        }
    }

    public static PurchaseOrderResponse from(PurchaseOrder order, List<PurchaseOrderItem> rows) {
        var supplier = order.getSupplier(); var warehouse = order.getWarehouse();
        List<Item> items = rows.stream().map(Item::from).sorted(Comparator.comparing(Item::boxSpec, BoxSnapshot.ORDER)).toList();
        return new PurchaseOrderResponse(order.getId(), order.getPoNo(), order.getProposal() == null ? null : order.getProposal().getId(),
                order.getStatus(), order.getCreatedAt(), order.getProposal() == null ? null : order.getProposal().getGeneratedAt(),
                order.getApprovedAt(), order.getReceivedAt(), order.getRequestedDeliveryDate(),
                supplier == null ? null : new SupplierInfo(supplier.getId(), supplier.getName(), supplier.getPaymentTerms(), supplier.getLeadTimeDays()),
                UserInfo.from(order.getRequestedBy()), UserInfo.from(order.getApprover()),
                new Recipient(warehouse.getId(), warehouse.getName(), warehouse.getAddress(), warehouse.getReceivingHours(), warehouse.getReceivingContact()),
                order.getDecisionReason(), totalQty(items), totalAmount(items), "KRW", false, order.getVersion(), items);
    }

    public static Long totalQty(List<Item> items) {
        return items.isEmpty() || items.stream().anyMatch(i -> i.qty() == null) ? null : items.stream().mapToLong(Item::qty).sum();
    }
    public static Long totalAmount(List<Item> items) {
        return items.isEmpty() || items.stream().anyMatch(i -> i.amountKrw() == null) ? null : items.stream().mapToLong(Item::amountKrw).reduce(0L, Math::addExact);
    }
}
