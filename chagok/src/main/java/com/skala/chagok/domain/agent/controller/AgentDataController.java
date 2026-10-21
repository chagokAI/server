package com.skala.chagok.domain.agent.controller;

import com.skala.chagok.domain.agent.dto.AgentDataDtos.*;
import com.skala.chagok.domain.agent.service.AgentDataService;
import com.skala.chagok.global.config.MatchingProperties;
import com.skala.chagok.global.response.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/**
 * 박스 주문 제안 에이전트(business-mcp) 전용 내부 API. docker network 안에서만 열고,
 * X-Internal-Token 헤더로 인증한다(SecurityConfig). 화면은 호출하지 않는다.
 */
@RestController @RequiredArgsConstructor @RequestMapping("/internal/v1")
public class AgentDataController {
    private final AgentDataService service;
    private final ApiResponses responses;
    private final MatchingProperties matching;

    @GetMapping("/matching-policy")
    public ApiResponse<MatchingPolicy> matchingPolicy() {
        return responses.of(new MatchingPolicy(matching.tilted().enabled(), matching.tilted().clearanceMm()));
    }

    @GetMapping("/warehouses")
    public ApiResponse<List<WarehouseItem>> warehouses() { return responses.of(service.warehouses()); }

    @GetMapping("/warehouses/{warehouseId}/box-specs")
    public ApiResponse<List<BoxSpecItem>> boxSpecs(@PathVariable UUID warehouseId,
                                                   @RequestParam(defaultValue = "true") boolean activeOnly) {
        return responses.of(service.boxSpecs(warehouseId, activeOnly));
    }

    @GetMapping("/warehouses/{warehouseId}/event-stats")
    public ApiResponse<EventStats> eventStats(@PathVariable UUID warehouseId) {
        var stats = service.eventStats(warehouseId);
        return responses.of(stats, stats.lastOccurredAt(), (int) stats.pendingEventCount());
    }

    @GetMapping("/warehouses/{warehouseId}/product-shipments")
    public ApiResponse<List<ProductShipment>> productShipments(@PathVariable UUID warehouseId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<String> statuses) {
        return responses.of(service.productShipments(warehouseId, from, to, statuses));
    }

    @GetMapping("/warehouses/{warehouseId}/monthly-shipments")
    public ApiResponse<List<MonthlyShipment>> monthlyShipments(@PathVariable UUID warehouseId,
            @RequestParam YearMonth fromMonth, @RequestParam YearMonth toMonth,
            @RequestParam(required = false) List<String> statuses) {
        return responses.of(service.monthlyShipments(warehouseId, fromMonth, toMonth, statuses));
    }

    @GetMapping("/warehouses/{warehouseId}/purchase-orders")
    public ApiResponse<List<PurchaseOrderItem>> purchaseOrders(@PathVariable UUID warehouseId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return responses.of(service.purchaseOrders(warehouseId, from, to));
    }

    @GetMapping("/warehouses/{warehouseId}/procurement-proposals")
    public ApiResponse<List<ProposalItem>> proposals(@PathVariable UUID warehouseId,
                                                     @RequestParam(defaultValue = "12") int limit) {
        return responses.of(service.proposals(warehouseId, limit));
    }

    @PutMapping("/warehouses/{warehouseId}/procurement-proposals/{proposalId}")
    public ApiResponse<ProposalSaveResponse> saveProposal(@PathVariable UUID warehouseId, @PathVariable UUID proposalId,
                                                          @RequestBody ProposalSaveRequest request) {
        return responses.of(service.saveProposal(warehouseId, proposalId, request));
    }
}
