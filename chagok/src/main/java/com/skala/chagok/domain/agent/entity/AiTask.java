package com.skala.chagok.domain.agent.entity;

import com.skala.chagok.domain.user.entity.AppUser;
import com.skala.chagok.domain.warehouse.entity.Warehouse;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 박스 주문 제안 에이전트 작업. 실행은 에이전트 서버가 하고, 여기는 요청자·승인자·상태·trace 링크를 남긴다.
 * id 를 에이전트 taskId(= LangGraph thread_id)로 그대로 쓴다.
 */
@Entity
@Table(name = "ai_task", indexes = @Index(name = "ix_ai_task_warehouse_created", columnList = "warehouse_id,created_at"))
@Getter @Builder @NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AiTask {
    public enum Status { RUNNING, WAITING_APPROVAL, DONE, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "warehouse_id", nullable = false) private Warehouse warehouse;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "requested_by") private AppUser requestedBy;
    @Column(name = "target_month", nullable = false, length = 7) private String targetMonth;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @Column(name = "trace_id", length = 64) private String traceId;
    /** LangSmith run 링크. 실패한 작업도 trace 로 원인을 찾을 수 있게 남긴다 */
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "trace_links", columnDefinition = "jsonb") private List<String> traceLinks;
    @Column(name = "proposal_id") private UUID proposalId;
    @Column(name = "total_amount_krw") private Long totalAmountKrw;
    @Column(name = "failure_message", length = 500) private String failureMessage;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "decided_by") private AppUser decidedBy;
    @Column(name = "decision", length = 20) private String decision;
    @Column(name = "decision_comment", length = 500) private String decisionComment;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Version private Integer version;

    public static AiTask start(Warehouse warehouse, AppUser user, String targetMonth, Instant now) {
        return AiTask.builder().warehouse(warehouse).requestedBy(user).targetMonth(targetMonth)
                .status(Status.RUNNING).createdAt(now).updatedAt(now).build();
    }

    public boolean active() { return status == Status.RUNNING || status == Status.WAITING_APPROVAL; }

    public void accepted(String traceId) { this.traceId = traceId; }

    public void decide(AppUser user, String decision, String comment, Instant now) {
        if (status != Status.WAITING_APPROVAL) throw new IllegalStateException("승인 대기 작업만 결정할 수 있습니다.");
        decidedBy = user; this.decision = decision; decisionComment = comment; status = Status.RUNNING; updatedAt = now;
    }

    public void waitApproval(Instant now) {
        if (active()) { status = Status.WAITING_APPROVAL; updatedAt = now; }
    }

    public void done(UUID proposalId, Long totalAmountKrw, List<String> links, Instant now) {
        if (!active()) return;
        this.proposalId = proposalId; this.totalAmountKrw = totalAmountKrw; traceLinks = links;
        status = Status.DONE; updatedAt = now; completedAt = now;
    }

    public void fail(String message, List<String> links, Instant now) {
        if (!active()) return;
        failureMessage = message == null ? null : message.substring(0, Math.min(500, message.length()));
        if (links != null && !links.isEmpty()) traceLinks = links;
        status = Status.FAILED; updatedAt = now; completedAt = now;
    }
}
