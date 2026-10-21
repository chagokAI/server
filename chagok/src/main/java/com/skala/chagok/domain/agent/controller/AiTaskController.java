package com.skala.chagok.domain.agent.controller;

import com.skala.chagok.domain.agent.dto.AiTaskDtos.*;
import com.skala.chagok.domain.agent.service.AiTaskService;
import com.skala.chagok.global.response.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 화면이 부르는 박스 주문 제안 AI 작업 API. 화면은 에이전트 서버를 직접 부르지 않고 여기만 부른다.
 * 실행·승인(POST)은 운영자 이상, 조회·진행 이벤트(GET)는 로그인 사용자(SecurityConfig).
 */
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/warehouses/{warehouseId}/ai-tasks")
public class AiTaskController {
    private final AiTaskService service;
    private final ApiResponses responses;

    @PostMapping
    public ResponseEntity<ApiResponse<TaskResponse>> create(@PathVariable UUID warehouseId,
                                                            @Valid @RequestBody CreateRequest request) {
        var task = service.create(warehouseId, request);
        return ResponseEntity.accepted().location(URI.create("/api/v1/warehouses/" + warehouseId + "/ai-tasks/" + task.id()))
                .body(responses.of(task));
    }

    @GetMapping
    public ApiResponse<List<TaskResponse>> list(@PathVariable UUID warehouseId, @RequestParam(defaultValue = "false") boolean mine) {
        return responses.of(service.list(warehouseId, mine));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskResponse> get(@PathVariable UUID warehouseId, @PathVariable UUID taskId) {
        return responses.of(service.get(warehouseId, taskId));
    }

    @GetMapping(value = "/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID warehouseId, @PathVariable UUID taskId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return service.stream(warehouseId, taskId, lastEventId);
    }

    @PostMapping("/{taskId}/approval")
    public ResponseEntity<ApiResponse<TaskResponse>> approval(@PathVariable UUID warehouseId, @PathVariable UUID taskId,
                                                              @Valid @RequestBody ApprovalRequest request) {
        return ResponseEntity.accepted().body(responses.of(service.decide(warehouseId, taskId, request)));
    }
}
