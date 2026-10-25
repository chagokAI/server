package com.skala.chagok.domain.notification.stream;

import com.skala.chagok.domain.notification.controller.NotificationStreamController;
import com.skala.chagok.domain.user.entity.AppUser;
import com.skala.chagok.domain.warehouse.entity.Warehouse;
import com.skala.chagok.global.auth.CurrentUserProvider;
import com.skala.chagok.global.web.WarehouseQueryGuard;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NotificationStreamControllerTest {
    @Test void rejectsOtherWarehousesAndAlwaysUsesTheAuthenticatedRecipient() throws Exception {
        var users = mock(CurrentUserProvider.class);
        var warehouses = mock(WarehouseQueryGuard.class);
        var hub = mock(NotificationStreamHub.class);
        var warehouse = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var user = mock(AppUser.class);
        var center = mock(Warehouse.class);
        when(user.getWarehouse()).thenReturn(center);
        when(center.getId()).thenReturn(warehouse);
        when(user.getId()).thenReturn(userId);
        when(users.getCurrentUser()).thenReturn(user);
        var mvc = MockMvcBuilders.standaloneSetup(new NotificationStreamController(users, warehouses, hub)).build();
        mvc.perform(get("/api/v1/warehouses/" + UUID.randomUUID() + "/notifications/stream")
                .accept("text/event-stream")).andExpect(status().isForbidden());
        verifyNoInteractions(hub);

        var session = new MockHttpSession();
        when(hub.subscribe(userId, session)).thenReturn(new SseEmitter());
        mvc.perform(get("/api/v1/warehouses/" + warehouse + "/notifications/stream").session(session)
                .accept("text/event-stream")).andExpect(status().isOk()).andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering", "no"));
        verify(hub).subscribe(userId, session);
    }
}
