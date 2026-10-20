package com.skala.chagok.domain.notification.dto;

import com.skala.chagok.domain.notification.entity.Notification;
import java.time.Instant;
import java.util.*;

public record NotificationPageResponse(List<Item> items, int page, int size, long totalElements,
                                        int totalPages, long unreadCount) {
    public enum Category { INVENTORY, PURCHASE, AI, MATCHING }
    public record Item(UUID id, String category, String title, String message, String link,
                       Instant createdAt, Instant readAt, boolean unread) {
        public static Item from(Notification value) {
            return new Item(value.getId(), value.getCategory(), value.getTitle() == null ? value.getMessage() : value.getTitle(),
                    value.getMessage(), value.getLink(), value.getCreatedAt(), value.getReadAt(), value.getReadAt() == null);
        }
    }
}
