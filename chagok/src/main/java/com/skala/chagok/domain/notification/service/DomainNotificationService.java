package com.skala.chagok.domain.notification.service;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.job.entity.AnalysisJob;
import com.skala.chagok.domain.notification.entity.Notification;
import com.skala.chagok.domain.notification.repository.NotificationRepository;
import com.skala.chagok.domain.user.repository.AppUserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import com.skala.chagok.domain.notification.dto.NotificationPageResponse;
import com.skala.chagok.domain.notification.stream.NotificationChanged;

/** 업무 변경과 알림 저장을 같은 트랜잭션으로 묶고 커밋 후 화면에 전달한다. */
@Service @RequiredArgsConstructor @Transactional
public class DomainNotificationService {
    /**
     * 라이브 재생 때문에 생긴 알림의 표시. 알림에는 출처 컬럼이 없고 스키마는 DB 담당 소유라, 이동 링크의 쿼리 인자로 남긴다.
     * 화면 이동 경로는 그대로이고, 라이브 초기화가 이 표시가 있는 알림만 지운다.
     */
    public static final String LIVE_ORIGIN_QUERY = "origin=live";

    private final AppUserRepository users;
    private final NotificationRepository notifications;
    private final ApplicationEventPublisher events;
    public static boolean shortage(BoxSpec box) {
        return box.getOnHandQty() != null && box.getSafetyQty() != null && box.getOnHandQty() < box.getSafetyQty();
    }
    public void inventory(BoxSpec box, boolean previouslyShort, boolean previouslyEnabled) {
        inventory(box, previouslyShort, previouslyEnabled, false);
    }

    /** @param liveOrigin 라이브 재생의 차감이 만든 변화인지. 그렇다면 초기화가 지울 수 있게 표시한다 */
    public void inventory(BoxSpec box, boolean previouslyShort, boolean previouslyEnabled, boolean liveOrigin) {
        if (Boolean.FALSE.equals(box.getAlertEnabled()) || !shortage(box) || previouslyShort && previouslyEnabled) return;
        publish(box.getWarehouse().getId(), "INVENTORY", shortageTitle(box),
                "현재 " + box.getOnHandQty() + "개 / 안전 재고 " + box.getSafetyQty() + "개",
                liveOrigin ? liveLink("/boxes/inventory") : "/boxes/inventory");
    }

    public static String shortageTitle(BoxSpec box) {
        return box.getCode() + " 안전 재고 미만";
    }

    public static String liveLink(String link) {
        return link + (link.contains("?") ? "&" : "?") + LIVE_ORIGIN_QUERY;
    }

    /** 자동 재계산은 조용히 갱신한다. 사용자가 요청한 작업은 제안서·비용 계산을 모두 저장한 뒤 한 번 알린다. */
    public void proposalCompleted(AnalysisJob job, boolean liveOrigin) {
        if (!requested(job) || job.getStatus() != AnalysisJob.Status.SUCCEEDED) return;
        publish(job.getWarehouse().getId(), "AI", "제안서 생성 완료",
                job.getTargetMonth() + " 제안서와 비용 계산이 완료되었습니다. 결과를 확인해 주세요.",
                liveOrigin ? liveLink("/orders") : "/orders");
    }

    public void proposalFailed(AnalysisJob job) {
        if (!requested(job) || job.getStatus() != AnalysisJob.Status.FAILED) return;
        publish(job.getWarehouse().getId(), "AI", "제안서 생성 실패",
                job.getTargetMonth() + " 제안서를 완성하지 못했습니다. 작업 상태에서 실패 원인을 확인해 주세요.", "/orders");
    }

    private static boolean requested(AnalysisJob job) {
        return job.getTrigger() == AnalysisJob.Trigger.MANUAL || job.getTrigger() == AnalysisJob.Trigger.RETRY;
    }

    public void publish(UUID warehouseId, String category, String title, String message, String link) {
        var saved = notifications.saveAllAndFlush(users.findByWarehouseId(warehouseId).stream()
                .filter(user -> !"DIRECTOR".equals(user.getRole()))
                .map(user -> Notification.builder()
                .recipient(user).category(category).title(title).message(message).link(link).build()).toList());
        saved.forEach(notification -> events.publishEvent(new NotificationChanged(
                notification.getRecipient().getId(), NotificationPageResponse.Item.from(notification))));
    }
}
