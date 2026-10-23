package com.skala.chagok.domain.warehouse.controller;

import com.skala.chagok.domain.warehouse.dto.WarehouseLocationResponse;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import com.skala.chagok.global.response.ApiResponse;
import com.skala.chagok.global.response.ApiResponses;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class WarehouseAdminController {

    private final WarehouseRepository warehouseRepository;
    private final ApiResponses apiResponses;

    /** 관리자 지도용 센터 목록. 좌표가 없는 센터는 핀을 찍을 수 없으므로 뺀다. */
    @GetMapping("/api/v1/admin/warehouses")
    @Transactional(readOnly = true)
    public ApiResponse<List<WarehouseLocationResponse>> getWarehouseLocations() {
        return apiResponses.of(warehouseRepository.findAllByLatitudeIsNotNullAndLongitudeIsNotNullOrderByName().stream()
                .map(WarehouseLocationResponse::from).toList());
    }
}
