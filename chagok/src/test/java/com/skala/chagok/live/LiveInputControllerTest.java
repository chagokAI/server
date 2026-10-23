package com.skala.chagok.live;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skala.chagok.dlanalysis.*;
import com.skala.chagok.global.response.ApiResponses;
import com.skala.chagok.global.web.WarehouseQueryGuard;
import jakarta.servlet.http.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

class LiveInputControllerTest {
    @Test void aSessionLostAfterAuthenticationCannotBeRecreatedToStartInput() {
        var input = mock(DlAnalysisReplayFeeder.class);
        @SuppressWarnings("unchecked") ObjectProvider<DlAnalysisReplayFeeder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(input);
        var controller = new LiveInputController(provider, mock(WarehouseQueryGuard.class), mock(JdbcTemplate.class), mock(ApiResponses.class));
        var request = mock(HttpServletRequest.class);
        assertThatThrownBy(() -> controller.change(UUID.randomUUID(), new LiveInputController.Change(true, false, null), request))
                .isInstanceOf(com.skala.chagok.global.error.BusinessException.class);
        verify(request, never()).getSession(true);
        verifyNoInteractions(input);
    }

    @Test void sessionEndingDuringLeaseRegistrationCannotLeaveTheInputRunning() {
        var input = mock(DlAnalysisReplayFeeder.class);
        @SuppressWarnings("unchecked") ObjectProvider<DlAnalysisReplayFeeder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(input);
        var controller = new LiveInputController(provider, mock(WarehouseQueryGuard.class), mock(JdbcTemplate.class), mock(ApiResponses.class));
        var request = mock(HttpServletRequest.class);
        var session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getId()).thenReturn("ending-session");
        doAnswer(call -> {
            var lease = (HttpSessionBindingListener) call.getArgument(1);
            lease.valueUnbound(new HttpSessionBindingEvent(session, call.getArgument(0)));
            return null;
        }).when(session).setAttribute(anyString(), any());
        UUID warehouse = UUID.randomUUID();

        controller.change(warehouse, new LiveInputController.Change(true, false, null), request);

        verify(input, never()).setRunning(eq(warehouse), eq(true), anyString());
    }
}
