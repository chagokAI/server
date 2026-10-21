package com.skala.chagok.domain.notification.dto;

/**
 * 알림 모두 읽음 결과.
 *
 * @param updatedCount 이번 요청으로 읽음 처리된 건수. 이미 읽은 알림은 포함하지 않는다.
 * @param unreadCount  처리 후 남은 미읽음 수. 조회의 unreadCount와 같은 기준(본인·해당 센터 전체)이다.
 */
public record NotificationReadAllResponse(long updatedCount, long unreadCount) {
}
