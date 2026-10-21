package com.skala.chagok.domain.notification.controller;

import com.skala.chagok.domain.notification.dto.*;
import com.skala.chagok.domain.notification.service.NotificationCommandService;
import com.skala.chagok.global.response.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/warehouses/{warehouseId}/notifications")
public class NotificationCommandController {
    private final NotificationCommandService service;
    private final ApiResponses responses;

    /** NOTI-API-02: 알림 단건 읽음 처리. */
    @PatchMapping("/{notificationId}/read")
    public ApiResponse<NotificationPageResponse.Item> read(@PathVariable UUID warehouseId,
            @PathVariable UUID notificationId) {
        return responses.of(service.read(warehouseId, notificationId));
    }

    /** NOTI-API-03: 알림 모두 읽음 처리. 본문 없이 호출하면 미읽음 전체가 대상이다. */
    @PostMapping("/read-all")
    public ApiResponse<NotificationReadAllResponse> readAll(@PathVariable UUID warehouseId,
            @RequestBody(required = false) NotificationReadAllRequest request) {
        return responses.of(service.readAll(warehouseId, request == null ? null : request.through()));
    }
}
