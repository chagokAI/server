package com.skala.chagok.domain.notification.controller;

import com.skala.chagok.domain.notification.dto.NotificationPageResponse;
import com.skala.chagok.domain.notification.service.NotificationQueryService;
import com.skala.chagok.global.response.*;
import jakarta.validation.constraints.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/warehouses/{warehouseId}/notifications")
public class NotificationQueryController {
    private final NotificationQueryService service;
    private final ApiResponses responses;

    @GetMapping
    public ApiResponse<NotificationPageResponse> list(@PathVariable UUID warehouseId,
            @RequestParam(required = false) NotificationPageResponse.Category category,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        return responses.of(service.list(warehouseId, category, unreadOnly, page, size, sort));
    }
}
