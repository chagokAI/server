package com.skala.chagok.domain.notification;

import com.skala.chagok.domain.job.entity.AnalysisJob;
import com.skala.chagok.domain.notification.repository.NotificationRepository;
import com.skala.chagok.domain.notification.service.DomainNotificationService;
import com.skala.chagok.domain.user.repository.AppUserRepository;
import com.skala.chagok.domain.warehouse.entity.Warehouse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProposalCompletionNotificationTest {
    final UUID warehouseId = UUID.randomUUID();
    final Warehouse warehouse = mock(Warehouse.class);
    final DomainNotificationService notices = spy(new DomainNotificationService(
            mock(AppUserRepository.class), mock(NotificationRepository.class), mock(ApplicationEventPublisher.class)));

    ProposalCompletionNotificationTest() {
        when(warehouse.getId()).thenReturn(warehouseId);
        doNothing().when(notices).publish(any(), anyString(), anyString(), anyString(), anyString());
    }

    AnalysisJob job(AnalysisJob.Trigger trigger) {
        var job = AnalysisJob.pending(warehouse, "2026-11", AnalysisJob.Type.PROCUREMENT_AND_COST, trigger, Instant.now());
        job.start(Instant.now());
        return job;
    }

    @Test void periodicRecalculationsNeverCreateSuccessOrFailureAlerts() {
        var success = job(AnalysisJob.Trigger.SCHEDULED);
        success.succeed(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), Instant.now());
        notices.proposalCompleted(success, true);
        var failed = job(AnalysisJob.Trigger.SCHEDULED);
        failed.fail("INSUFFICIENT_OBSERVATIONS", "자료 없음", Instant.now());
        notices.proposalFailed(failed);
        verify(notices, never()).publish(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void requestedCalculationNotifiesOnceOnlyAfterBothResultsAreSaved() {
        var job = job(AnalysisJob.Trigger.MANUAL);
        notices.proposalCompleted(job, false);
        verify(notices, never()).publish(any(), anyString(), anyString(), anyString(), anyString());
        job.succeed(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), Instant.now());
        notices.proposalCompleted(job, false);
        verify(notices).publish(eq(warehouseId), eq("AI"), eq("제안서 생성 완료"), contains("2026-11"), eq("/orders"));
    }

    @Test void retryCompletionRetainsTheLiveOriginForResetCleanup() {
        var job = job(AnalysisJob.Trigger.RETRY);
        job.succeed(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), Instant.now());
        notices.proposalCompleted(job, true);
        verify(notices).publish(eq(warehouseId), eq("AI"), eq("제안서 생성 완료"), anyString(), eq("/orders?origin=live"));
    }

    @Test void aFailedRequestProducesFailureInformationAndNeverACompletionAlert() {
        var job = job(AnalysisJob.Trigger.MANUAL);
        job.fail("NO_ACTIVE_BOX_SPECS", "활성 박스 없음", Instant.now());
        notices.proposalCompleted(job, false);
        notices.proposalFailed(job);
        verify(notices).publish(eq(warehouseId), eq("AI"), eq("제안서 생성 실패"), anyString(), eq("/orders"));
        verify(notices, never()).publish(any(), anyString(), eq("제안서 생성 완료"), anyString(), anyString());
    }
}
