package com.skala.chagok.domain.agent.dto;

import com.skala.chagok.domain.agent.entity.AiTask;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 화면이 부르는 AI 작업(박스 주문 제안 에이전트) API 형식 */
public final class AiTaskDtos {
    private AiTaskDtos() {}

    public record CreateRequest(@NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String targetMonth) {}

    public record ApprovalRequest(@NotNull @Pattern(regexp = "APPROVED|REJECTED") String decision,
                                  @Size(max = 500) String comment) {}

    public record TaskResponse(UUID id, String targetMonth, AiTask.Status status, String requestedBy,
                               Instant createdAt, Instant updatedAt, Instant completedAt, String traceId,
                               List<String> traceLinks, UUID proposalId, Long totalAmountKrw, String failureMessage,
                               String decision, String decisionComment, String decidedBy) {
        public static TaskResponse from(AiTask t) {
            return new TaskResponse(t.getId(), t.getTargetMonth(), t.getStatus(),
                    t.getRequestedBy() == null ? null : t.getRequestedBy().getName(), t.getCreatedAt(), t.getUpdatedAt(),
                    t.getCompletedAt(), t.getTraceId(), t.getTraceLinks() == null ? List.of() : t.getTraceLinks(),
                    t.getProposalId(), t.getTotalAmountKrw(), t.getFailureMessage(), t.getDecision(),
                    t.getDecisionComment(), t.getDecidedBy() == null ? null : t.getDecidedBy().getName());
        }
    }
}
