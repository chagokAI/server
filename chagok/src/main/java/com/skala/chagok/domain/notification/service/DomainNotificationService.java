package com.skala.chagok.domain.notification.service;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.notification.entity.Notification;
import com.skala.chagok.domain.notification.repository.NotificationRepository;
import com.skala.chagok.domain.user.repository.AppUserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 업무 변경 트랜잭션 안에서 센터 사용자에게 알림을 저장한다. 외부 메시지는 전송하지 않는다. */
@Service @RequiredArgsConstructor
public class DomainNotificationService {
    private final AppUserRepository users;
    private final NotificationRepository notifications;
    public static boolean shortage(BoxSpec box) {
        return box.getOnHandQty() != null && box.getSafetyQty() != null && box.getOnHandQty() < box.getSafetyQty();
    }
    public void inventory(BoxSpec box, boolean previouslyShort, boolean previouslyEnabled) {
        if (Boolean.FALSE.equals(box.getAlertEnabled()) || !shortage(box) || previouslyShort && previouslyEnabled) return;
        publish(box.getWarehouse().getId(), "INVENTORY", box.getCode() + " 안전 재고 미만",
                "현재 " + box.getOnHandQty() + "개 / 안전 재고 " + box.getSafetyQty() + "개", "/boxes/inventory");
    }
    public void publish(UUID warehouseId, String category, String title, String message, String link) {
        notifications.saveAll(users.findByWarehouseId(warehouseId).stream().map(user -> Notification.builder()
                .recipient(user).category(category).title(title).message(message).link(link).build()).toList());
    }
}
