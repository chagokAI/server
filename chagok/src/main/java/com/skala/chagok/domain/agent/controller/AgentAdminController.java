package com.skala.chagok.domain.agent.controller;

import com.skala.chagok.domain.agent.client.AgentClient;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.error.ErrorCode;
import com.skala.chagok.global.response.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 관리자(DIRECTOR) 화면 '센터별 리포트/지침서' API. 본부 리포트와 센터 지침서는 에이전트 서버가 만들고 저장하므로
 * 여기서는 그대로 중계만 한다(SecurityConfig: /api/v1/admin/** 는 DIRECTOR).
 */
@Validated @RestController @RequiredArgsConstructor @RequestMapping("/api/v1/admin")
public class AgentAdminController {
    private final AgentClient agent;
    private final ApiResponses responses;

    public record GuideUpdateRequest(@NotNull Map<String, Object> changes) {}

    public record ReportCreateRequest(@NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String month) {}

    public record ApprovalRequest(@NotNull @Pattern(regexp = "APPROVED|REJECTED") String decision,
                                  @Size(max = 500) String comment) {}

    public record PolicyUpdateRequest(@NotBlank @Size(max = 50000) String markdown) {}

    /** 만들어 둔 본부 통합 효율화 리포트 월 목록 */
    @GetMapping("/hq-reports")
    public ApiResponse<Map<String, Object>> reports() {
        return responses.of(call(() -> agent.get("/internal/agent/hq-reports")));
    }

    /** 리포트 만들기 전 센터별 제안서 현황(그 월까지 저장된 제안서가 있어야 효과를 모을 수 있다) */
    @GetMapping("/hq-reports/readiness")
    public ApiResponse<Map<String, Object>> readiness(@RequestParam @Pattern(regexp = "\\d{4}-\\d{2}") String month) {
        return responses.of(call(() -> agent.get("/internal/agent/hq-reports/readiness?month=" + month)));
    }

    /** 본부 리포트 만들기(비동기). 센터 규칙을 바꾸는 조치가 있으면 승인 대기로 멈춘다 */
    @PostMapping("/hq-reports")
    public ApiResponse<Map<String, Object>> createReport(@Valid @RequestBody ReportCreateRequest request) {
        return responses.of(call(() -> agent.post("/internal/agent/hq-reports", Map.of("month", request.month()))));
    }

    /** 리포트 작업 상태 {status, pending(승인할 조치), result} */
    @GetMapping("/hq-reports/tasks/{taskId}")
    public ApiResponse<Map<String, Object>> reportTask(@PathVariable @Pattern(regexp = "hq-[\\w-]+") String taskId) {
        return responses.of(call(() -> agent.get("/internal/agent/tasks/" + taskId)));
    }

    /** 리포트 조치(센터 규칙 변경) 승인·반려 */
    @PostMapping("/hq-reports/tasks/{taskId}/approval")
    public ApiResponse<Map<String, Object>> approveReport(@PathVariable @Pattern(regexp = "hq-[\\w-]+") String taskId,
                                                         @Valid @RequestBody ApprovalRequest request) {
        return responses.of(call(() -> agent.post("/internal/agent/tasks/" + taskId + "/approval",
                Map.of("decision", request.decision(), "comment", request.comment() == null ? "" : request.comment()))));
    }

    /** 그 월의 본부 리포트 HTML {month, html} */
    @GetMapping("/hq-reports/{month:\\d{4}-\\d{2}}")
    public ApiResponse<Map<String, Object>> report(@PathVariable String month) {
        return responses.of(call(() -> agent.get("/internal/agent/hq-reports/" + month)));
    }

    /** 지침서 화면 센터 목록 */
    @GetMapping("/guides/centers")
    public ApiResponse<Map<String, Object>> centers() {
        return responses.of(call(() -> agent.get("/internal/agent/centers")));
    }

    /** 센터 지침서: 센터별로 조정할 수 있는 규칙 + 그 값으로 채운 규정 문서 */
    @GetMapping("/guides/{warehouseId}")
    public ApiResponse<Map<String, Object>> guide(@PathVariable UUID warehouseId) {
        return responses.of(call(() -> agent.get("/internal/agent/centers/" + warehouseId + "/guide")));
    }

    /** 규칙 조정 저장 {changes: {키: 값 | null(기본값으로)}}. 다음 박스 주문 제안부터 적용된다 */
    @PatchMapping("/guides/{warehouseId}")
    public ApiResponse<Map<String, Object>> updateGuide(@PathVariable UUID warehouseId,
                                                       @Valid @RequestBody GuideUpdateRequest request) {
        return responses.of(call(() -> agent.patch("/internal/agent/centers/" + warehouseId + "/guide",
                Map.of("changes", request.changes()))));
    }

    /** 규정 문서 원문 틀(고치기용). 전 센터 공통 문서이고 {규칙 키} 자리는 센터마다 기준값으로 채워진다 */
    @GetMapping("/policies/{docId}")
    public ApiResponse<Map<String, Object>> policy(@PathVariable @Pattern(regexp = "POL-\\d+") String docId) {
        return responses.of(call(() -> agent.get("/internal/agent/policies/" + docId)));
    }

    /** 규정 문서 수정(전 센터 공통). 다음 제안서의 규정 인용부터 반영된다 */
    @PutMapping("/policies/{docId}")
    public ApiResponse<Map<String, Object>> updatePolicy(@PathVariable @Pattern(regexp = "POL-\\d+") String docId,
                                                        @Valid @RequestBody PolicyUpdateRequest request) {
        return responses.of(call(() -> agent.put("/internal/agent/policies/" + docId, Map.of("markdown", request.markdown()))));
    }

    private Map<String, Object> call(Supplier<Map<String, Object>> request) {
        try {
            return request.get();
        } catch (AgentClient.AgentRejectedException e) {
            throw new BusinessException(e.status() == 404 ? ErrorCode.NOT_FOUND : ErrorCode.VALIDATION_ERROR, e.getMessage());
        } catch (AgentClient.AgentUnavailableException e) {
            throw new BusinessException(ErrorCode.TEMPORARILY_UNAVAILABLE, "AI 에이전트에 연결할 수 없습니다. 잠시 후 다시 시도하세요.");
        }
    }
}
