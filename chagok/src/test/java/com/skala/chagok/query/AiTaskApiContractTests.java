package com.skala.chagok.query;

import com.skala.chagok.domain.agent.controller.AiTaskController;
import com.skala.chagok.domain.agent.dto.AiTaskDtos.*;
import com.skala.chagok.domain.agent.entity.AiTask;
import com.skala.chagok.domain.agent.service.AiTaskService;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.*;
import com.skala.chagok.global.response.ApiResponses;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiTaskApiContractTests {
    private final UUID warehouseId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final AiTaskService service = mock(AiTaskService.class);
    private final ApiResponses responses = new ApiResponses(new ChagokProperties(ZoneId.of("Asia/Seoul"), 300,
            new ChagokProperties.Inventory(3, 14), new ChagokProperties.BoxSize(10, 50)));
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new AiTaskController(service, responses))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private String path(String suffix) { return "/api/v1/warehouses/" + warehouseId + "/ai-tasks" + suffix; }

    private TaskResponse task(AiTask.Status status) {
        return new TaskResponse(taskId, "2026-11", status, "운영자", Instant.now(), Instant.now(), null, "trace-1",
                List.of(), null, null, null, null, null, null);
    }

    @Test void createReturns202WithLocation() throws Exception {
        when(service.create(eq(warehouseId), any())).thenReturn(task(AiTask.Status.RUNNING));
        mvc.perform(post(path("")).contentType(MediaType.APPLICATION_JSON).content("{\"targetMonth\":\"2026-11\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/warehouses/" + warehouseId + "/ai-tasks/" + taskId))
                .andExpect(jsonPath("$.data.status").value("RUNNING")).andExpect(jsonPath("$.data.traceId").value("trace-1"));
    }

    @Test void createRejectsBadMonth() throws Exception {
        mvc.perform(post(path("")).contentType(MediaType.APPLICATION_JSON).content("{\"targetMonth\":\"2026-13\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void approvalAcceptsOnlyApprovedOrRejected() throws Exception {
        mvc.perform(post(path("/" + taskId + "/approval")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());
        when(service.decide(eq(warehouseId), eq(taskId), any())).thenReturn(task(AiTask.Status.RUNNING));
        mvc.perform(post(path("/" + taskId + "/approval")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECTED\",\"comment\":\"안전 재고 넉넉하게\"}"))
                .andExpect(status().isAccepted());
    }

    @Test void approvalOnFinishedTaskIsConflict() throws Exception {
        when(service.decide(eq(warehouseId), eq(taskId), any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_STATE, "승인 대기 중인 작업이 아닙니다."));
        mvc.perform(post(path("/" + taskId + "/approval")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isConflict());
    }

    @Test void taskStatusFollowsAgentEvents() {
        var t = AiTask.start(null, null, "2026-11", Instant.now());
        t.waitApproval(Instant.now());
        Assertions.assertEquals(AiTask.Status.WAITING_APPROVAL, t.getStatus());
        t.decide(null, "APPROVED", null, Instant.now());
        Assertions.assertEquals(AiTask.Status.RUNNING, t.getStatus());
        t.done(UUID.randomUUID(), 1_068_000L, List.of("https://smith.langchain.com/r/x"), Instant.now());
        Assertions.assertEquals(AiTask.Status.DONE, t.getStatus());
        t.fail("늦게 온 실패", null, Instant.now());                 // 끝난 작업은 바뀌지 않는다
        Assertions.assertEquals(AiTask.Status.DONE, t.getStatus());
        Assertions.assertThrows(IllegalStateException.class, () -> t.decide(null, "APPROVED", null, Instant.now()));
    }
}
