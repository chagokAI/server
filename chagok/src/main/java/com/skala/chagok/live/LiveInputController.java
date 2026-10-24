package com.skala.chagok.live;

import com.skala.chagok.domain.event.LiveEventIds;
import com.skala.chagok.dlanalysis.DlAnalysisReplayFeeder;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.error.ErrorCode;
import com.skala.chagok.global.response.ApiResponse;
import com.skala.chagok.global.response.ApiResponses;
import com.skala.chagok.global.web.WarehouseQueryGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** 읽기 구독과 별개인 물류 입력 제어. 센터 권한·운영자·CSRF는 기존 보안 필터를 거친다. */
@RestController
class LiveInputController {
    private final ObjectProvider<DlAnalysisReplayFeeder> feeder;
    private final WarehouseQueryGuard warehouses;
    private final JdbcTemplate jdbc;
    private final ApiResponses responses;

    LiveInputController(ObjectProvider<DlAnalysisReplayFeeder> feeder, WarehouseQueryGuard warehouses,
            JdbcTemplate jdbc, ApiResponses responses) {
        this.feeder = feeder; this.warehouses = warehouses; this.jdbc = jdbc; this.responses = responses;
    }

    record Change(@NotNull Boolean running, Boolean ownedOnly,
            @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String controlId) {}
    record State(boolean available, boolean running, long pending) {}

    @GetMapping("/api/v1/warehouses/{warehouseId}/live-input")
    ApiResponse<State> state(@PathVariable UUID warehouseId) {
        warehouses.require(warehouseId);
        return responses.of(read(warehouseId));
    }

    @PutMapping("/api/v1/warehouses/{warehouseId}/live-input")
    ApiResponse<State> change(@PathVariable UUID warehouseId, @Valid @RequestBody Change change,
            HttpServletRequest request) {
        warehouses.require(warehouseId);
        var input = feeder.getIfAvailable();
        if (input == null) throw new BusinessException(ErrorCode.TEMPORARILY_UNAVAILABLE, "물류 입력기가 활성화되어 있지 않습니다.");
        var session = request.getSession(false);
        if (session == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED, "세션이 종료되었습니다. 다시 로그인해 주세요.");
        String controlId = change.controlId() == null ? "session" : change.controlId();
        String owner = session.getId() + ":" + controlId;
        String leaseKey = LiveInputController.class.getName() + ":" + warehouseId + ":" + controlId;
        if (change.running()) {
            // 등록 중 로그아웃되면 종료 콜백이 먼저 올 수 있다. 닫힌 lease는 이후 시작하지 않는다.
            var lease = new InputLease(input, warehouseId, owner);
            session.setAttribute(leaseKey, lease);
            lease.start();
        } else {
            // 화면 이탈은 이전 화면의 실행만 종료한다. 명시 Pause의 센터 정지와 구별한다.
            if (Boolean.TRUE.equals(change.ownedOnly())) input.stopOwnedBy(warehouseId, owner);
            else input.setRunning(warehouseId, false, owner);
            session.removeAttribute(leaseKey);
        }
        return responses.of(read(warehouseId));
    }

    private State read(UUID warehouseId) {
        var input = feeder.getIfAvailable();
        boolean running = input != null && input.isRunning(warehouseId);
        Long pending = jdbc.queryForObject("select count(*) from item_event where warehouse_id=? and event_id like ? and status='PENDING'",
                Long.class, warehouseId, LiveEventIds.PREFIX + "%");
        return new State(input != null, running, pending == null ? 0 : pending);
    }

    private static final class InputLease implements HttpSessionBindingListener {
        private final DlAnalysisReplayFeeder feeder;
        private final UUID warehouseId;
        private final String owner;
        private boolean closed;
        InputLease(DlAnalysisReplayFeeder feeder, UUID warehouseId, String owner) {
            this.feeder = feeder; this.warehouseId = warehouseId; this.owner = owner;
        }
        synchronized void start() {
            if (!closed) feeder.setRunning(warehouseId, true, owner);
        }
        @Override public synchronized void valueUnbound(HttpSessionBindingEvent event) {
            closed = true;
            feeder.stopOwnedBy(warehouseId, owner);
        }
    }
}
