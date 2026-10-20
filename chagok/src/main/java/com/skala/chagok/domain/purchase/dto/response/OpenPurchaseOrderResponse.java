package com.skala.chagok.domain.purchase.dto.response;
import com.skala.chagok.domain.box.dto.BoxSnapshot;
import java.time.LocalDate;
import java.util.*;
public record OpenPurchaseOrderResponse(UUID id, String poNo, String supplierName, LocalDate deliveryDate,
        List<Item> items) {
    public record Item(BoxSnapshot boxSpec, int orderedQty, long receivedQty, long remainingQty) {}
}
