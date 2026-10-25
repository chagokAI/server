package com.skala.chagok.domain.notification.service;

import com.skala.chagok.domain.notification.dto.*;
import com.skala.chagok.domain.notification.entity.Notification;
import com.skala.chagok.domain.notification.repository.NotificationRepository;
import com.skala.chagok.domain.user.entity.AppUser;
import com.skala.chagok.global.auth.CurrentUserProvider;
import com.skala.chagok.global.error.*;
import com.skala.chagok.global.web.WarehouseQueryGuard;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import com.skala.chagok.domain.notification.stream.NotificationChanged;

@Service @RequiredArgsConstructor @Transactional
public class NotificationCommandService {
    private final NotificationRepository notifications;
    private final CurrentUserProvider users;
    private final WarehouseQueryGuard warehouses;
    private final ApplicationEventPublisher events;

    /** 알림 한 건을 읽음 처리한다. 이미 읽은 알림이면 처음 읽은 시각을 그대로 두고 현재 상태를 돌려준다. */
    public NotificationPageResponse.Item read(UUID warehouseId, UUID notificationId) {
        AppUser user = requireMember(warehouseId);
        Notification notification = notifications
                .findByIdAndRecipientIdAndRecipientWarehouseId(notificationId, user.getId(), warehouseId)
                .orElseThrow(() -> BusinessException.notFound("알림"));
        notification.markRead(Instant.now());
        events.publishEvent(new NotificationChanged(user.getId(), null));
        return NotificationPageResponse.Item.from(notification);
    }

    /** 본인의 해당 센터 미읽음 알림을 한 번에 읽음 처리한다. through를 주면 그 시각 이전 생성분만 처리한다. */
    public NotificationReadAllResponse readAll(UUID warehouseId, Instant through) {
        AppUser user = requireMember(warehouseId);
        Instant now = Instant.now();
        int updated = notifications.markReadUntil(user.getId(), warehouseId, through == null ? now : through, now);
        long unread = notifications.countByRecipientIdAndRecipientWarehouseIdAndReadAtIsNull(user.getId(), warehouseId);
        if (updated > 0) events.publishEvent(new NotificationChanged(user.getId(), null));
        return new NotificationReadAllResponse(updated, unread);
    }

    /** 알림은 본인 것만 다룰 수 있으므로 조회와 같은 기준으로 센터 소속을 확인한다. */
    private AppUser requireMember(UUID warehouseId) {
        warehouses.require(warehouseId);
        AppUser user = users.getCurrentUser();
        if (user.getWarehouse() == null || !warehouseId.equals(user.getWarehouse().getId()))
            throw new BusinessException(ErrorCode.FORBIDDEN);
        return user;
    }
}
