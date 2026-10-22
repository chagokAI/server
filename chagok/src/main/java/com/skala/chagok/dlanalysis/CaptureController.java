package com.skala.chagok.dlanalysis;

import com.skala.chagok.global.error.*;
import com.skala.chagok.global.response.*;
import java.net.URI;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequiredArgsConstructor @KafkaIntegrationEnabled
@RequestMapping({"/api/v1/warehouses/{warehouseId}/captures", "/internal/v1/warehouses/{warehouseId}/captures"})
public class CaptureController {
    private final CaptureService service;
    private final ApiResponses responses;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CaptureService.Accepted>> upload(@PathVariable UUID warehouseId,
            @RequestPart("images") List<MultipartFile> images) {
        try {
            var result = service.upload(warehouseId, images);
            return ResponseEntity.accepted()
                    .location(URI.create("/api/v1/warehouses/" + warehouseId + "/captures/" + result.eventId()))
                    .body(responses.of(result));
        } catch (DlAnalysisRequestPublishException e) {
            throw new BusinessException(ErrorCode.TEMPORARILY_UNAVAILABLE, "사진 분석 요청을 전달하지 못했습니다. 다시 시도하세요.");
        }
    }

    @GetMapping("/{eventId}")
    public ApiResponse<CaptureService.Result> get(@PathVariable UUID warehouseId, @PathVariable UUID eventId) {
        return responses.of(service.get(warehouseId, eventId.toString()));
    }
}
