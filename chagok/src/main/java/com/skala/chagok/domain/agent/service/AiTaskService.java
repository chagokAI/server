package com.skala.chagok.domain.agent.service;

import com.skala.chagok.domain.agent.client.AgentClient;
import com.skala.chagok.domain.agent.dto.AiTaskDtos.*;
import com.skala.chagok.domain.agent.entity.AiTask;
import com.skala.chagok.global.auth.CurrentUserProvider;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.error.ErrorCode;
import java.io.IOException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 박스 주문 제안 AI 작업: 접수(202) → 에이전트 서버 실행 → 진행 이벤트 SSE 중계 → 승인·반려 → 완료.
 * LLM 은 호출하지 않는다(에이전트 서버 몫). 요청자·승인자와 trace 링크를 작업 이력에 남긴다.
 */
@Slf4j @Service @RequiredArgsConstructor
public class AiTaskService {
    private static final long STREAM_TIMEOUT_MS = 15 * 60 * 1000L;
    private final AiTaskRecorder recorder;
    private final AgentClient agent;
    private final CurrentUserProvider users;

    /** 같은 센터·대상 월에 진행 중인 작업이 있으면 그 작업을 돌려준다(에이전트는 같은 제안 id 를 덮어쓰므로 동시 실행 금지) */
    public TaskResponse create(UUID warehouseId, CreateRequest request) {
        var active = recorder.active(warehouseId, request.targetMonth());
        if (active.isPresent()) return active.get();
        if (!agent.configured()) throw new BusinessException(ErrorCode.TEMPORARILY_UNAVAILABLE, "AI 에이전트가 연결되지 않았습니다.");
        UUID id = recorder.start(warehouseId, users.getCurrentUser(), request.targetMonth());
        try {
            var accepted = agent.createTask(id.toString(), warehouseId, request.targetMonth());
            recorder.accepted(id, accepted.get("traceId") == null ? null : String.valueOf(accepted.get("traceId")));
        } catch (AgentClient.AgentUnavailableException e) {
            recorder.failed(id, e.getMessage());
            throw new BusinessException(ErrorCode.TEMPORARILY_UNAVAILABLE, "AI 에이전트를 실행하지 못했습니다. 잠시 후 다시 시도하세요.");
        }
        return recorder.view(warehouseId, id);
    }

    public TaskResponse get(UUID warehouseId, UUID id) {
        var task = recorder.view(warehouseId, id);
        boolean active = task.status() == AiTask.Status.RUNNING || task.status() == AiTask.Status.WAITING_APPROVAL;
        if (active && agent.configured()) {
            try {
                return recorder.sync(id, agent.getTask(id.toString()));
            } catch (AgentClient.AgentUnavailableException e) {
                log.warn("AI 작업 {} 상태 확인 실패: {}", id, e.getMessage());
            }
        }
        return task;
    }

    public List<TaskResponse> list(UUID warehouseId, boolean mine) {
        return recorder.list(warehouseId, mine ? users.getCurrentUser().getId() : null);
    }

    /** 승인·반려를 기록(결정한 사람)하고 에이전트를 같은 thread_id 로 재개한다 */
    public TaskResponse decide(UUID warehouseId, UUID id, ApprovalRequest request) {
        recorder.decide(warehouseId, id, users.getCurrentUser(), request.decision(), request.comment());
        try {
            agent.approve(id.toString(), request.decision(), request.comment());
        } catch (AgentClient.AgentUnavailableException e) {
            recorder.reopenApproval(id);
            throw new BusinessException(ErrorCode.TEMPORARILY_UNAVAILABLE, "AI 에이전트에 결정을 전달하지 못했습니다. 다시 시도하세요.");
        }
        return recorder.view(warehouseId, id);
    }

    /** 에이전트 진행 이벤트(TOOL_START·TOOL_END·TOKEN·APPROVAL_REQUIRED·DONE·FAILED)를 화면으로 중계한다 */
    public SseEmitter stream(UUID warehouseId, UUID id, String lastEventId) {
        recorder.view(warehouseId, id);
        var emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        var closed = new java.util.concurrent.atomic.AtomicBoolean();      // 화면 연결이 끊기면 중계를 멈춘다
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));
        Thread.ofVirtual().name("ai-task-sse-" + id).start(() -> {
            try {
                agent.stream(id.toString(), lastEventId, event -> {
                    recorder.apply(id, event);
                    if (closed.get()) return false;
                    try {
                        var out = SseEmitter.event().name(event.name()).data(event.data());
                        if (event.id() != null) out.id(event.id());
                        emitter.send(out);
                        return true;
                    } catch (IOException | IllegalStateException e) {
                        return false;                       // 화면 연결이 끊김
                    }
                });
                emitter.complete();
            } catch (AgentClient.AgentUnavailableException e) {
                try {
                    emitter.send(SseEmitter.event().name("FAILED").data(Map.of("error", e.getMessage())));
                } catch (IOException | IllegalStateException ignored) { }
                emitter.complete();
            }
        });
        return emitter;
    }
}
