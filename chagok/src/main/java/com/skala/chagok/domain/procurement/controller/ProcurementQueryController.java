package com.skala.chagok.domain.procurement.controller;

import com.skala.chagok.domain.procurement.dto.*;
import com.skala.chagok.domain.procurement.service.ProcurementQueryService;
import com.skala.chagok.global.response.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/warehouses/{warehouseId}")
public class ProcurementQueryController {
    private final ProcurementQueryService service;
    private final ApiResponses responses;

    @GetMapping("/procurement-proposals/latest")
    public ApiResponse<LatestResult<ProposalResponse>> latestProposal(@PathVariable UUID warehouseId,
                                                                      @RequestParam String targetMonth) {
        var result = service.latestProposal(warehouseId, targetMonth);
        return responses.of(result, result.result() == null ? null : result.result().dataAsOf(), null);
    }

    @GetMapping("/procurement-proposals/{proposalId}")
    public ApiResponse<ProposalResponse> proposal(@PathVariable UUID warehouseId, @PathVariable UUID proposalId) {
        var result = service.proposal(warehouseId, proposalId);
        return responses.of(result, result.dataAsOf(), null);
    }

    @GetMapping("/cost-forecasts/latest")
    public ApiResponse<LatestResult<CostForecastResponse>> latestCost(@PathVariable UUID warehouseId,
                                                                      @RequestParam String targetMonth) {
        var result = service.latestCost(warehouseId, targetMonth);
        return responses.of(result, result.result() == null ? null : result.result().dataAsOf(), null);
    }
}
