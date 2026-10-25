package com.skala.chagok.domain.agent.service;

import com.skala.chagok.domain.agent.client.AgentClient;
import com.skala.chagok.domain.agent.dto.AiTaskDtos.TaskResponse;
import com.skala.chagok.domain.agent.entity.AiTask;
import com.skala.chagok.global.auth.CurrentUserProvider;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AiTaskRecoveryTest {
    private final AiTaskRecorder recorder = mock(AiTaskRecorder.class);
    private final AgentClient agent = mock(AgentClient.class);
    private final AiTaskService service = new AiTaskService(recorder, agent, mock(CurrentUserProvider.class));

    @Test void confirmedMissingAgentTaskReleasesTheActiveTask() {
        UUID warehouse = UUID.randomUUID(), id = UUID.randomUUID();
        var active = mock(TaskResponse.class);
        var failed = mock(TaskResponse.class);
        when(active.status()).thenReturn(AiTask.Status.RUNNING);
        when(recorder.view(warehouse, id)).thenReturn(active, failed);
        when(agent.configured()).thenReturn(true);
        when(agent.getTask(id.toString())).thenThrow(new AgentClient.AgentRejectedException(404, "없는 작업"));

        assertThat(service.get(warehouse, id)).isSameAs(failed);
        verify(recorder).failed(eq(id), contains("재시작"));
    }

    @Test void temporaryConnectionFailureKeepsTheTaskActive() {
        UUID warehouse = UUID.randomUUID(), id = UUID.randomUUID();
        var active = mock(TaskResponse.class);
        when(active.status()).thenReturn(AiTask.Status.WAITING_APPROVAL);
        when(recorder.view(warehouse, id)).thenReturn(active);
        when(agent.configured()).thenReturn(true);
        when(agent.getTask(id.toString())).thenThrow(new AgentClient.AgentUnavailableException("연결 실패", null));

        assertThat(service.get(warehouse, id)).isSameAs(active);
        verify(recorder, never()).failed(any(), any());
    }
}
