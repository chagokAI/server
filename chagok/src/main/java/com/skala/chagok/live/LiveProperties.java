package com.skala.chagok.live;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 라이브 재고 스트림 설정. 입력기와 초기화는 기본 꺼짐이며 원격 DB는 명시적인 별도 허용이 필요하다.
 *
 * @param maxSubscribersPerWarehouse 센터당 동시 구독 상한. 넘으면 503으로 거절한다
 * @param retryAfterSeconds          상한 초과 응답의 Retry-After 값
 * @param heartbeatMs                연결 유지 신호 간격
 * @param feeder                     이미 DL 처리된 표본을 새 이벤트로 흘리는 입력기
 * @param reset                      라이브 이벤트가 바꾼 재고를 되돌리는 초기화
 */
@ConfigurationProperties(prefix = "chagok.live")
public record LiveProperties(
        @DefaultValue("20") int maxSubscribersPerWarehouse,
        @DefaultValue("10") int retryAfterSeconds,
        @DefaultValue("15000") long heartbeatMs,
        @DefaultValue Feeder feeder,
        @DefaultValue Reset reset) {

    /** @param intervalMs 표본 투입 고정 주기. 167ms는 목표 약 6건/초이며 실제 처리량 보장은 아니다 */
    public record Feeder(@DefaultValue("false") boolean enabled, @DefaultValue("167") long intervalMs) {
    }

    /** @param cron 초기화 주기(센터 시간대 기준) */
    public record Reset(@DefaultValue("false") boolean enabled, @DefaultValue("0 */30 * * * *") String cron) {
    }
}
