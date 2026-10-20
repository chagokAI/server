package com.skala.chagok.domain.box.controller;

import com.skala.chagok.domain.box.dto.BoxSpecCreateRequest;
import com.skala.chagok.domain.box.dto.BoxSpecResponse;
import com.skala.chagok.domain.box.dto.BoxSpecPageResponse;
import com.skala.chagok.domain.box.dto.BoxSpecUpdateRequest;
import com.skala.chagok.domain.box.service.BoxSpecService;
import com.skala.chagok.global.response.ApiResponse;
import com.skala.chagok.global.response.ApiResponses;
import com.skala.chagok.global.response.PageResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/warehouses/{warehouseId}/box-specs")
public class BoxSpecController {

    private final BoxSpecService boxSpecService;
    private final ApiResponses apiResponses;

    /** SPEC-API-01 */
    @GetMapping
    public ApiResponse<BoxSpecPageResponse> getBoxSpecs(
            @PathVariable UUID warehouseId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean custom,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        return apiResponses.of(boxSpecService.getBoxSpecs(warehouseId, keyword, active, custom, page, size, sort));
    }

    /** SPEC-API-02: ERD에 규격 버전 이력 테이블이 없어 versionId는 지원하지 않는다. */
    @GetMapping("/{boxSpecId}")
    public ApiResponse<BoxSpecResponse> getBoxSpec(@PathVariable UUID warehouseId, @PathVariable UUID boxSpecId) {
        return apiResponses.of(boxSpecService.getBoxSpec(warehouseId, boxSpecId));
    }

    /** SPEC-API-03 */
    @PostMapping
    public ResponseEntity<ApiResponse<BoxSpecResponse>> createBoxSpec(@PathVariable UUID warehouseId,
                                                                      @Valid @RequestBody BoxSpecCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(apiResponses.of(boxSpecService.createBoxSpec(warehouseId, request)));
    }

    /** SPEC-API-04 */
    @PatchMapping("/{boxSpecId}")
    public ApiResponse<BoxSpecResponse> updateBoxSpec(@PathVariable UUID warehouseId,
                                                      @PathVariable UUID boxSpecId,
                                                      @Valid @RequestBody BoxSpecUpdateRequest request) {
        return apiResponses.of(boxSpecService.updateBoxSpec(warehouseId, boxSpecId, request));
    }

    /** SPEC-API-05 */
    @DeleteMapping("/{boxSpecId}")
    public ResponseEntity<Void> deleteBoxSpec(@PathVariable UUID warehouseId,
                                              @PathVariable UUID boxSpecId,
                                              @RequestParam Integer expectedVersion) {
        boxSpecService.deleteBoxSpec(warehouseId, boxSpecId, expectedVersion);
        return ResponseEntity.noContent().build();
    }
}
