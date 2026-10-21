package com.skala.chagok.domain.supplier.dto;

import com.skala.chagok.domain.supplier.entity.Supplier;

import java.util.UUID;

public record SupplierResponse(UUID id, String name, String paymentTerms, Short leadTimeDays) {

    public static SupplierResponse from(Supplier supplier) {
        return new SupplierResponse(supplier.getId(), supplier.getName(), supplier.getPaymentTerms(),
                supplier.getLeadTimeDays());
    }
}
