package com.skala.chagok.domain.purchase.controller;

import com.skala.chagok.domain.purchase.dto.request.*;
import com.skala.chagok.domain.purchase.dto.response.*;
import com.skala.chagok.domain.purchase.service.PurchaseOrderWorkflowService;
import com.skala.chagok.global.response.*;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/warehouses/{warehouseId}/purchase-orders")
public class PurchaseOrderWorkflowController {
    private final PurchaseOrderWorkflowService service;
    private final ApiResponses responses;
    @PostMapping("/from-proposal")
    public ResponseEntity<ApiResponse<DraftGroupResponse>> draft(@PathVariable UUID warehouseId,
            @Valid @RequestBody DraftFromProposalRequest request) {
        var result = service.fromProposal(warehouseId, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(responses.of(result.data()));
    }
    @PatchMapping("/{orderId}")
    public ApiResponse<PurchaseOrderResponse> update(@PathVariable UUID warehouseId, @PathVariable UUID orderId,
            @Valid @RequestBody PurchaseOrderUpdateRequest request) {
        return responses.of(service.update(warehouseId, orderId, request));
    }
    @GetMapping("/open")
    public ApiResponse<List<OpenPurchaseOrderResponse>> open(@PathVariable UUID warehouseId,
            @RequestParam(required = false) UUID boxSpecId) {
        return responses.of(service.open(warehouseId, boxSpecId));
    }
}
