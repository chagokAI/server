package com.skala.chagok.domain.event;

/**
 * 라이브 재생이 만든 물류 이벤트를 eventId로 구별한다.
 *
 * <p>라이브 입력기는 측정 표본을 새 이벤트로 재생하고, 30분 초기화가 그 이벤트와 그 영향을 되돌린다. 재고 차감·알림·예측처럼
 * 이벤트를 소비하는 쪽이 라이브 여부를 같은 규칙으로 판정해야 초기화가 빠짐없이 되돌릴 수 있다. 그 규칙을 이벤트 도메인에
 * 두어 재고·예측 도메인이 라이브 기능 패키지에 의존하지 않게 했다.
 */
public final class LiveEventIds {
    public static final String PREFIX = "live-";

    private LiveEventIds() {
    }

    public static boolean isLive(String eventId) {
        return eventId != null && eventId.startsWith(PREFIX);
    }
}
