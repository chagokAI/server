package com.skala.chagok.domain.product.controller;

import com.skala.chagok.domain.product.dto.MatchOverrideRequest;
import com.skala.chagok.domain.product.dto.ProductDetailResponse;
import com.skala.chagok.domain.product.dto.ProductMatchResponse;
import com.skala.chagok.domain.product.dto.ProductUpdateRequest;
import com.skala.chagok.domain.product.entity.MatchStatus;
import com.skala.chagok.domain.product.repository.SearchBy;
import com.skala.chagok.domain.product.service.ProductMatchService;
import com.skala.chagok.global.response.ApiResponse;
import com.skala.chagok.global.response.ApiResponses;
import com.skala.chagok.global.response.PageResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/warehouses/{warehouseId}")
public class ProductMatchController {

    private final ProductMatchService productMatchService;
    private final ApiResponses apiResponses;

    /** MATCH-API-01 */
    @GetMapping("/product-matches")
    public ApiResponse<PageResponse<ProductMatchResponse>> getProductMatches(
            @PathVariable UUID warehouseId,
            @RequestParam(defaultValue = "NAME") SearchBy searchBy,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) MatchStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        return apiResponses.of(productMatchService.getProductMatches(
                warehouseId, searchBy, keyword, categoryId, status, page, size, sort));
    }

    /** MATCH-API-02 */
    @GetMapping("/products/{productId}")
    public ApiResponse<ProductDetailResponse> getProduct(@PathVariable UUID warehouseId,
                                                         @PathVariable UUID productId) {
        return apiResponses.of(productMatchService.getProduct(warehouseId, productId));
    }

    /** MATCH-API-03 */
    @PatchMapping("/products/{productId}")
    public ApiResponse<ProductDetailResponse> updateProduct(@PathVariable UUID warehouseId,
                                                            @PathVariable UUID productId,
                                                            @Valid @RequestBody ProductUpdateRequest request) {
        return apiResponses.of(productMatchService.updateProduct(warehouseId, productId, request));
    }

    /** MATCH-API-04 */
    @PutMapping("/products/{productId}/box-match")
    public ApiResponse<ProductMatchResponse> overrideMatch(@PathVariable UUID warehouseId,
                                                           @PathVariable UUID productId,
                                                           @Valid @RequestBody MatchOverrideRequest request) {
        return apiResponses.of(productMatchService.overrideMatch(warehouseId, productId, request));
    }

    /** MATCH-API-05: 실제 삭제하지 않고 비활성화한다. */
    @DeleteMapping("/products/{productId}")
    public ResponseEntity<Void> deleteProduct(@PathVariable UUID warehouseId,
                                              @PathVariable UUID productId,
                                              @RequestParam Integer expectedVersion) {
        productMatchService.deactivateProduct(warehouseId, productId, expectedVersion);
        return ResponseEntity.noContent().build();
    }
}
