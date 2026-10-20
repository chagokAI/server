package com.skala.chagok.domain.purchase.controller;

import com.skala.chagok.domain.purchase.dto.response.*;
import com.skala.chagok.domain.purchase.entity.PurchaseOrderStatus;
import com.skala.chagok.domain.purchase.service.PurchaseOrderQueryService;
import com.skala.chagok.global.response.*;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/warehouses/{warehouseId}/purchase-orders")
public class PurchaseOrderQueryController {
    private final PurchaseOrderQueryService service;
    private final ApiResponses responses;

    @GetMapping
    public ApiResponse<PurchaseOrderPageResponse> list(@PathVariable UUID warehouseId,
            @RequestParam LocalDate from, @RequestParam LocalDate to, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) PurchaseOrderStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, @RequestParam(required = false) String sort) {
        return responses.of(service.list(warehouseId, from, to, keyword, status, page, size, sort));
    }

    @GetMapping("/{purchaseOrderId}")
    public ApiResponse<PurchaseOrderResponse> get(@PathVariable UUID warehouseId, @PathVariable UUID purchaseOrderId) {
        return responses.of(service.get(warehouseId, purchaseOrderId));
    }
}
