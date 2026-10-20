package com.skala.chagok.domain.purchase.controller;

import com.skala.chagok.domain.purchase.dto.request.PurchaseOrderCreateRequest;
import com.skala.chagok.domain.purchase.dto.response.PurchaseOrderCreateResponse;
import com.skala.chagok.domain.purchase.service.PurchaseOrderService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/purchase-orders")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    /** 발주 제안서를 승인해 박스 주문 발주서를 생성한다. */
    @PostMapping
    public ResponseEntity<PurchaseOrderCreateResponse> create(
            @Valid @RequestBody PurchaseOrderCreateRequest request) {
        PurchaseOrderCreateResponse response = purchaseOrderService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
