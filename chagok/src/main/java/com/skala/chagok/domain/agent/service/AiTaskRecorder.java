package com.skala.chagok.domain.agent.service;

import com.skala.chagok.domain.agent.client.AgentClient;
import com.skala.chagok.domain.agent.dto.AiTaskDtos.TaskResponse;
import com.skala.chagok.domain.agent.entity.AiTask;
import com.skala.chagok.domain.agent.repository.AiTaskRepository;
import com.skala.chagok.domain.user.entity.AppUser;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.error.ErrorCode;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** AI 작업 상태 기록·조회. 에이전트 호출(느린 외부 HTTP)과 분리해 짧은 트랜잭션으로만 쓰고,
 * 응답 변환(요청자·승인자 이름 = 지연 로딩)도 트랜잭션 안에서 끝낸다(open-in-view 꺼짐). */
@Component @RequiredArgsConstructor
public class AiTaskRecorder {
    private final AiTaskRepository tasks;
    private final WarehouseRepository warehouses;

    @Transactional
    public UUID start(UUID warehouseId, AppUser user, String targetMonth) {
        var warehouse = warehouses.findById(warehouseId).orElseThrow(() -> BusinessException.notFound("센터"));
        return tasks.saveAndFlush(AiTask.start(warehouse, user, targetMonth, Instant.now())).getId();
    }

    @Transactional(readOnly = true)
    public TaskResponse view(UUID warehouseId, UUID id) {
        return TaskResponse.from(tasks.findByIdAndWarehouseId(id, warehouseId)
                .orElseThrow(() -> BusinessException.notFound("AI 작업")));
    }

    @Transactional(readOnly = true)
    public Optional<TaskResponse> active(UUID warehouseId, String targetMonth) {
        return tasks.findFirstByWarehouseIdAndTargetMonthAndStatusInOrderByCreatedAtDesc(warehouseId, targetMonth,
                List.of(AiTask.Status.RUNNING, AiTask.Status.WAITING_APPROVAL)).map(TaskResponse::from);
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> list(UUID warehouseId, UUID userId) {
        var page = org.springframework.data.domain.PageRequest.of(0, 20);
        var rows = userId == null ? tasks.findByWarehouseIdOrderByCreatedAtDesc(warehouseId, page)
                : tasks.findByWarehouseIdAndRequestedByIdOrderByCreatedAtDesc(warehouseId, userId, page);
        return rows.stream().map(TaskResponse::from).toList();
    }

    @Transactional
    public void accepted(UUID id, String traceId) { tasks.findById(id).ifPresent(t -> t.accepted(traceId)); }

    @Transactional
    public void failed(UUID id, String message) { tasks.findById(id).ifPresent(t -> t.fail(message, null, Instant.now())); }

    @Transactional
    public void decide(UUID warehouseId, UUID id, AppUser user, String decision, String comment) {
        var task = tasks.findByIdAndWarehouseId(id, warehouseId).orElseThrow(() -> BusinessException.notFound("AI 작업"));
        if (task.getStatus() != AiTask.Status.WAITING_APPROVAL)
            throw new BusinessException(ErrorCode.INVALID_STATE, "승인 대기 중인 작업이 아닙니다.");
        task.decide(user, decision, comment, Instant.now());
    }

    @Transactional
    public void reopenApproval(UUID id) { tasks.findById(id).ifPresent(t -> t.waitApproval(Instant.now())); }

    /** 에이전트 진행 이벤트로 상태를 옮긴다(SSE 중계 중) */
    @Transactional
    public void apply(UUID id, AgentClient.Event event) {
        tasks.findById(id).ifPresent(t -> {
            var d = event.data();
            if (t.getTraceId() == null && d.get("traceId") != null) t.accepted(String.valueOf(d.get("traceId")));
            switch (event.name()) {
                case "APPROVAL_REQUIRED" -> t.waitApproval(Instant.now());
                case "DONE" -> t.done(uuid(d.get("proposalId")), number(d.get("totalAmount")), links(d.get("langsmith")), Instant.now());
                case "FAILED" -> t.fail(String.valueOf(d.get("error")), links(d.get("langsmith")), Instant.now());
                default -> { }
            }
        });
    }

    /** 화면이 SSE 를 보지 않았을 때를 위해 조회 시 에이전트 상태로 맞춘다 */
    @Transactional
    public TaskResponse sync(UUID id, Map<String, Object> agentTask) {
        var t = tasks.findById(id).orElseThrow(() -> BusinessException.notFound("AI 작업"));
        Object result = agentTask.get("result");
        Map<?, ?> r = result instanceof Map<?, ?> m ? m : Map.of();
        switch (String.valueOf(agentTask.get("status"))) {
            case "WAITING_APPROVAL" -> t.waitApproval(Instant.now());
            case "DONE" -> t.done(uuid(r.get("proposalId")), number(r.get("totalAmount")), links(r.get("langsmith")), Instant.now());
            case "FAILED" -> t.fail(String.valueOf(agentTask.get("error")), null, Instant.now());
            default -> { }
        }
        return TaskResponse.from(t);
    }

    private static UUID uuid(Object v) {
        try { return v == null ? null : UUID.fromString(String.valueOf(v)); } catch (IllegalArgumentException e) { return null; }
    }

    private static Long number(Object v) { return v instanceof Number n ? n.longValue() : null; }

    private static List<String> links(Object v) {
        return v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : null;
    }
}
