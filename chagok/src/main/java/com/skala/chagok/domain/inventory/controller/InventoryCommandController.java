package com.skala.chagok.domain.inventory.controller;

import com.skala.chagok.domain.inventory.dto.*;
import com.skala.chagok.domain.inventory.service.InventoryCommandService;
import com.skala.chagok.global.response.*;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/warehouses/{warehouseId}")
public class InventoryCommandController {
    private final InventoryCommandService service;
    private final ApiResponses responses;
    @GetMapping("/box-inventories/{boxSpecId}/movements")
    public ApiResponse<PageResponse<InventoryMovementResponse>> history(@PathVariable UUID warehouseId, @PathVariable UUID boxSpecId,
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int page,
            @RequestParam(defaultValue = "20") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) int size) {
        return responses.of(service.history(warehouseId, boxSpecId, page, size));
    }
    @PostMapping("/inventory-receipts")
    public ResponseEntity<ApiResponse<InventoryMovementResponse>> receive(@PathVariable UUID warehouseId,
            @Valid @RequestBody InventoryReceiptRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.of(service.receive(warehouseId, request)));
    }
    @PostMapping("/inventory-adjustments")
    public ResponseEntity<ApiResponse<InventoryMovementResponse>> adjust(@PathVariable UUID warehouseId,
            @Valid @RequestBody InventoryAdjustmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.of(service.adjust(warehouseId, request)));
    }
}
