package com.skala.chagok.domain.notification.dto;

import java.time.Instant;

/**
 * 알림 모두 읽음 요청.
 *
 * @param through 이 시각 이전에 생성된 알림만 읽음 처리한다. 생략하면 미읽음 전체가 대상이다.
 *                목록을 불러온 뒤 도착한 새 알림까지 지우지 않으려면 목록 조회 시각을 보낸다.
 */
public record NotificationReadAllRequest(Instant through) {
}
