package com.skala.chagok.domain.notification.repository;

import com.skala.chagok.domain.notification.entity.Notification;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {
    long countByRecipientIdAndRecipientWarehouseIdAndReadAtIsNull(UUID recipientId, UUID warehouseId);
}
