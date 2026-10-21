package com.skala.chagok.domain.supplier.controller;

import com.skala.chagok.domain.supplier.dto.SupplierResponse;
import com.skala.chagok.domain.supplier.service.SupplierService;
import com.skala.chagok.global.response.ApiResponse;
import com.skala.chagok.global.response.ApiResponses;

import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/warehouses/{warehouseId}/suppliers")
public class SupplierController {

    private final SupplierService supplierService;
    private final ApiResponses apiResponses;

    /** COMMON-API-02: 규격 추가·수정 모달과 발주서의 공급사 드롭다운. 이름순 전체 목록. */
    @GetMapping
    public ApiResponse<List<SupplierResponse>> getSuppliers(@PathVariable UUID warehouseId,
                                                            @RequestParam(required = false) String keyword) {
        return apiResponses.of(supplierService.getSuppliers(warehouseId, keyword));
    }
}
