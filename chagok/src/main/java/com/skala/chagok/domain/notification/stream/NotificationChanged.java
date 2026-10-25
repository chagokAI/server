package com.skala.chagok.domain.notification.stream;

import com.skala.chagok.domain.notification.dto.NotificationPageResponse;
import java.util.UUID;

/** notification은 신규 알림일 때만 채운다. 읽음 변경은 목록 새로고침만 요청한다. */
public record NotificationChanged(UUID recipientId, NotificationPageResponse.Item notification) { }
