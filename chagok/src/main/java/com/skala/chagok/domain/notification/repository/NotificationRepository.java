package com.skala.chagok.domain.notification.repository;

import com.skala.chagok.domain.notification.entity.Notification;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {
    long countByRecipientIdAndRecipientWarehouseIdAndReadAtIsNull(UUID recipientId, UUID warehouseId);

    Optional<Notification> findByIdAndRecipientIdAndRecipientWarehouseId(UUID id, UUID recipientId, UUID warehouseId);

    /** 본인의 해당 센터 미읽음 알림 중 through 이전 생성분을 한 번에 읽음 처리하고 처리 건수를 돌려준다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification n set n.readAt = :readAt
            where n.recipient.id = :recipientId and n.recipient.warehouse.id = :warehouseId
              and n.readAt is null and n.createdAt <= :through
            """)
    int markReadUntil(@Param("recipientId") UUID recipientId, @Param("warehouseId") UUID warehouseId,
                      @Param("through") Instant through, @Param("readAt") Instant readAt);
}
