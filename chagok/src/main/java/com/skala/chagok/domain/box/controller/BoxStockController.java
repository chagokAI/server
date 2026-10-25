package com.skala.chagok.domain.box.controller;

import com.skala.chagok.domain.box.dto.BoxStockPageResponse;
import com.skala.chagok.domain.box.service.BoxStockService;
import com.skala.chagok.global.response.ApiResponse;
import com.skala.chagok.global.response.ApiResponses;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/warehouses/{warehouseId}/box-stocks")
public class BoxStockController {

    private final BoxStockService boxStockService;
    private final ApiResponses apiResponses;

    /** BOXSTOCK-API-01: 박스 재고/규격 관리 화면의 목록·연결 상품 수·규격별 재고·사용 여부 개수·요약 */
    @GetMapping
    public ApiResponse<BoxStockPageResponse> getBoxStocks(
            @PathVariable UUID warehouseId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean custom,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        return apiResponses.of(boxStockService.getBoxStocks(warehouseId, keyword, active, custom, page, size, sort));
    }
}
