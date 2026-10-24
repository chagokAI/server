package com.skala.chagok.dlanalysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.skala.chagok.live.LiveResetScheduler;
import com.skala.chagok.live.LiveResetService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

class LiveFeatureStartupTest {
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(DlAnalysisReplayFeeder.class, LiveResetScheduler.class)
                .withBean(DlAnalysisReplaySource.class, () -> mock(DlAnalysisReplaySource.class))
                .withBean(DlAnalysisResultProducer.class, () -> mock(DlAnalysisResultProducer.class))
                .withBean(DlAnalysisItemEventRepository.class, () -> mock(DlAnalysisItemEventRepository.class))
                .withBean(EntityManager.class, () -> mock(EntityManager.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(LiveResetService.class, () -> mock(LiveResetService.class));
    }

    @Test void bothFeaturesAreAbsentByDefaultEvenForARemoteDatabase() {
        context().withPropertyValues("spring.datasource.url=jdbc:postgresql://remote/chagok").run(ctx ->
                assertThat(ctx).hasNotFailed().doesNotHaveBean(DlAnalysisReplayFeeder.class).doesNotHaveBean(LiveResetScheduler.class));
    }
    @Test void enabledFeederRefusesRemoteDatabaseOnStartup() {
        context().withPropertyValues("spring.datasource.url=jdbc:postgresql://remote/chagok", "chagok.live.feeder.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().hasRootCauseInstanceOf(IllegalStateException.class));
    }
    @Test void enabledResetRefusesRemoteDatabaseOnStartup() {
        context().withPropertyValues("spring.datasource.url=jdbc:postgresql://remote/chagok", "chagok.live.reset.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().hasRootCauseInstanceOf(IllegalStateException.class));
    }
    @Test void bothFeaturesStartForLoopbackDatabase() {
        context().withPropertyValues("spring.datasource.url=jdbc:postgresql://127.0.0.1/chagok",
                "chagok.live.feeder.enabled=true", "chagok.live.reset.enabled=true").run(ctx ->
                assertThat(ctx).hasNotFailed().hasSingleBean(DlAnalysisReplayFeeder.class).hasSingleBean(LiveResetScheduler.class));
    }
    @Test void explicitlyApprovedRemoteDatabaseCanStartBothFeatures() {
        context().withPropertyValues("spring.datasource.url=jdbc:postgresql://cloud-db:15432/chagok",
                "chagok.live.allow-remote-database=true", "chagok.live.feeder.enabled=true", "chagok.live.reset.enabled=true")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(DlAnalysisReplayFeeder.class).hasSingleBean(LiveResetScheduler.class));
    }
}
