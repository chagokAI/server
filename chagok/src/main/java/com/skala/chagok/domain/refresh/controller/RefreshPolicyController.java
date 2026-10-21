package com.skala.chagok.domain.refresh.controller;

import com.skala.chagok.domain.refresh.dto.RefreshPolicyResponse;
import com.skala.chagok.domain.refresh.service.RefreshPolicyService;
import com.skala.chagok.global.response.ApiResponse;
import com.skala.chagok.global.response.ApiResponses;

import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/warehouses/{warehouseId}/refresh-policy")
public class RefreshPolicyController {

    private final RefreshPolicyService refreshPolicyService;
    private final ApiResponses apiResponses;

    /** COMMON-API-03: 화면 재조회 주기와 정기 분석 배치 일정 */
    @GetMapping
    public ApiResponse<RefreshPolicyResponse> getPolicy(@PathVariable UUID warehouseId) {
        return apiResponses.of(refreshPolicyService.getPolicy(warehouseId));
    }
}
