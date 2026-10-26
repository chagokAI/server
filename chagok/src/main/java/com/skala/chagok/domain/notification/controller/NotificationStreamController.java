package com.skala.chagok.domain.notification.controller;

import com.skala.chagok.domain.notification.stream.NotificationStreamHub;
import com.skala.chagok.global.auth.CurrentUserProvider;
import com.skala.chagok.global.error.*;
import com.skala.chagok.global.web.WarehouseQueryGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/warehouses/{warehouseId}/notifications")
public class NotificationStreamController {
    private final CurrentUserProvider users;
    private final WarehouseQueryGuard warehouses;
    private final NotificationStreamHub hub;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable UUID warehouseId, HttpServletRequest request) {
        try {
            warehouses.require(warehouseId);
            var user = users.getCurrentUser();
            if ("DIRECTOR".equals(user.getRole()) || user.getWarehouse() == null || !warehouseId.equals(user.getWarehouse().getId()))
                throw new BusinessException(ErrorCode.FORBIDDEN);
            return ResponseEntity.ok().header("Cache-Control", "no-cache").header("X-Accel-Buffering", "no")
                    .body(hub.subscribe(user.getId(), request.getSession(false)));
        } catch (BusinessException error) {
            return ResponseEntity.status(error.getErrorCode().getStatus()).build();
        }
    }
}
