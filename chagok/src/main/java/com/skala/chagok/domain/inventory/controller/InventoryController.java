package com.skala.chagok.domain.inventory.controller;

import com.skala.chagok.domain.inventory.dto.InventoryItemResponse;
import com.skala.chagok.domain.inventory.dto.InventoryPolicyUpdateRequest;
import com.skala.chagok.domain.inventory.service.InventoryService;
import com.skala.chagok.global.response.ApiResponse;
import com.skala.chagok.global.response.ApiResponses;

import jakarta.validation.Valid;

import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/warehouses/{warehouseId}/box-inventories")
public class InventoryController {

    private final InventoryService inventoryService;
    private final ApiResponses apiResponses;

    /** INV-API-02: inventoryId는 박스 규격 id다. 목록은 BOXSTOCK-API-01이 규격과 함께 내려준다. */
    @PatchMapping("/{inventoryId}/policy")
    public ApiResponse<InventoryItemResponse> updatePolicy(@PathVariable UUID warehouseId,
                                                           @PathVariable UUID inventoryId,
                                                           @Valid @RequestBody InventoryPolicyUpdateRequest request) {
        return apiResponses.of(inventoryService.updatePolicy(warehouseId, inventoryId, request));
    }
}
