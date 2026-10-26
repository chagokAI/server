package com.skala.chagok.domain.notification;

import com.skala.chagok.domain.notification.entity.Notification;
import com.skala.chagok.domain.notification.repository.NotificationRepository;
import com.skala.chagok.domain.notification.service.*;
import com.skala.chagok.domain.notification.stream.NotificationChanged;
import com.skala.chagok.domain.user.entity.AppUser;
import com.skala.chagok.domain.user.repository.AppUserRepository;
import com.skala.chagok.domain.warehouse.entity.Warehouse;
import com.skala.chagok.global.auth.CurrentUserProvider;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.error.ErrorCode;
import com.skala.chagok.global.web.WarehouseQueryGuard;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectorNotificationTest {
    private final UUID warehouseId = UUID.randomUUID();
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final WarehouseQueryGuard warehouses = mock(WarehouseQueryGuard.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private AppUser user(String role) {
        return AppUser.builder().id(UUID.randomUUID()).role(role)
                .warehouse(Warehouse.builder().id(warehouseId).build()).build();
    }

    @Test void publicationStoresAndStreamsOnlyToCenterStaff() {
        var users = mock(AppUserRepository.class);
        var operator = user("OPERATOR");
        var viewer = user("VIEWER");
        when(users.findByWarehouseId(warehouseId)).thenReturn(List.of(operator, user("DIRECTOR"), viewer));
        when(notifications.saveAllAndFlush(anyList())).thenAnswer(call -> call.getArgument(0));
        new DomainNotificationService(users, notifications, events).publish(warehouseId, "AI", "완료", "내용", "/orders");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Notification>> saved = ArgumentCaptor.forClass(List.class);
        verify(notifications).saveAllAndFlush(saved.capture());
        assertThat(saved.getValue()).extracting(n -> n.getRecipient().getId())
                .containsExactly(operator.getId(), viewer.getId());
        var streamed = ArgumentCaptor.forClass(NotificationChanged.class);
        verify(events, times(2)).publishEvent(streamed.capture());
        assertThat(streamed.getAllValues()).extracting(NotificationChanged::recipientId)
                .containsExactly(operator.getId(), viewer.getId());
    }

    @Test void directorGetsNoExistingNotificationsOrUnreadBadge() {
        when(currentUser.getCurrentUser()).thenReturn(user("DIRECTOR"));
        var page = new NotificationQueryService(notifications, currentUser, warehouses)
                .list(warehouseId, null, false, 0, 20, null);
        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(page.unreadCount()).isZero();
        verifyNoInteractions(notifications);
    }

    @Test void directorCannotRetrieveAnExistingNotificationThroughReadEndpoints() {
        when(currentUser.getCurrentUser()).thenReturn(user("DIRECTOR"));
        var commands = new NotificationCommandService(notifications, currentUser, warehouses, events);
        assertThatThrownBy(() -> commands.read(warehouseId, UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> commands.readAll(warehouseId, null))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(notifications, events);
    }
}
