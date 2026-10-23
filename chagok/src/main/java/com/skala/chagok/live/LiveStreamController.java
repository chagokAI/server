package com.skala.chagok.live;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.skala.chagok.global.web.WarehouseQueryGuard;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.error.ErrorCode;

/**
 * 센터의 실시간 재고·포장 결과 스트림. 읽기 전용이며 로그인 사용자의 센터 대조는 보안 필터가 한다.
 * 이벤트: snapshot(현재고), packed·waiting·failed(확정된 DL 결과 처리).
 */
@RestController
@RequestMapping("/api/v1/warehouses/{warehouseId}")
class LiveStreamController {
    private final WarehouseQueryGuard warehouses;
    private final LiveStreamHub hub;
    @org.springframework.beans.factory.annotation.Value("${chagok.auth.enabled:true}")
    private boolean requireSession;

    LiveStreamController(WarehouseQueryGuard warehouses, LiveStreamHub hub) {
        this.warehouses = warehouses;
        this.hub = hub;
    }

    @GetMapping(value = "/live-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> stream(@PathVariable UUID warehouseId, jakarta.servlet.http.HttpServletRequest request) {
        try {
            warehouses.require(warehouseId);
            var session = request.getSession(false);
            if (requireSession && session == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
            return ResponseEntity.ok().body(hub.subscribe(warehouseId, session));
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.NOT_FOUND && e.getErrorCode() != ErrorCode.TEMPORARILY_UNAVAILABLE
                    && e.getErrorCode() != ErrorCode.UNAUTHENTICATED)
                throw e;
            // SSE 요청은 JSON 오류 본문을 협상할 수 없다. 연결 전 거절은 상태와 재시도 헤더로만 전달한다.
            var response = ResponseEntity.status(e.getErrorCode().getStatus());
            if (e.getDetails() != null && e.getDetails().get("retryAfterSeconds") instanceof Number seconds)
                response.header("Retry-After", String.valueOf(seconds.longValue()));
            return response.build();
        }
    }
}
