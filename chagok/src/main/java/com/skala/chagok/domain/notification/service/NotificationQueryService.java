package com.skala.chagok.domain.notification.service;

import com.skala.chagok.domain.notification.dto.NotificationPageResponse;
import com.skala.chagok.domain.notification.entity.Notification;
import com.skala.chagok.domain.notification.repository.NotificationRepository;
import com.skala.chagok.global.auth.CurrentUserProvider;
import com.skala.chagok.global.error.*;
import com.skala.chagok.global.web.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class NotificationQueryService {
    private final NotificationRepository notifications;
    private final CurrentUserProvider users;
    private final WarehouseQueryGuard warehouses;

    public NotificationPageResponse list(UUID warehouseId, NotificationPageResponse.Category category,
                                           boolean unreadOnly, int page, int size, String sort) {
        warehouses.require(warehouseId);
        var user = users.getCurrentUser();
        var pageable = PageRequests.of(page, size, sort, Map.of("createdAt", "createdAt"), "createdAt,desc");
        if ("DIRECTOR".equals(user.getRole()))
            return new NotificationPageResponse(List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0, 0, 0);
        if (user.getWarehouse() == null || !warehouseId.equals(user.getWarehouse().getId()))
            throw new BusinessException(ErrorCode.FORBIDDEN);
        UUID userId = user.getId();
        Specification<Notification> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("recipient").get("id"), userId),
                cb.equal(root.get("recipient").get("warehouse").get("id"), warehouseId));
        if (category != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("category"), category.name()));
        if (unreadOnly) spec = spec.and((root, query, cb) -> cb.isNull(root.get("readAt")));
        var result = notifications.findAll(spec, pageable);
        return new NotificationPageResponse(result.stream().map(NotificationPageResponse.Item::from).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
                notifications.countByRecipientIdAndRecipientWarehouseIdAndReadAtIsNull(userId, warehouseId));
    }
}
