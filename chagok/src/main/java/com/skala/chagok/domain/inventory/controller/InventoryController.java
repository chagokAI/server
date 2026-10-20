package com.skala.chagok.domain.inventory.controller;

import com.skala.chagok.domain.inventory.dto.InventoryItemResponse;
import com.skala.chagok.domain.inventory.dto.InventoryPageResponse;
import com.skala.chagok.domain.inventory.dto.InventoryPolicyUpdateRequest;
import com.skala.chagok.domain.inventory.dto.InventoryStatus;
import com.skala.chagok.domain.inventory.service.InventoryService;
import com.skala.chagok.global.response.ApiResponse;
import com.skala.chagok.global.response.ApiResponses;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/warehouses/{warehouseId}/box-inventories")
public class InventoryController {

    private final InventoryService inventoryService;
    private final ApiResponses apiResponses;

    /** INV-API-01 */
    @GetMapping
    public ApiResponse<InventoryPageResponse> getInventories(
            @PathVariable UUID warehouseId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) InventoryStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        return apiResponses.of(inventoryService.getInventories(warehouseId, keyword, status, page, size, sort));
    }

    /** INV-API-02: inventoryId는 박스 규격 id다. */
    @PatchMapping("/{inventoryId}/policy")
    public ApiResponse<InventoryItemResponse> updatePolicy(@PathVariable UUID warehouseId,
                                                           @PathVariable UUID inventoryId,
                                                           @Valid @RequestBody InventoryPolicyUpdateRequest request) {
        return apiResponses.of(inventoryService.updatePolicy(warehouseId, inventoryId, request));
    }
}
