package com.skala.chagok.domain.refresh.dto;

import com.skala.chagok.domain.job.entity.AnalysisJob;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * COMMON-API-03. 화면 재조회 주기와 정기 분석 배치 일정.
 *
 * @param refreshAfterSeconds 모든 화면 공통 권장 재조회 간격. 각 응답 meta.refreshAfterSeconds와 같은 값
 * @param timezone            센터 시간대. 시각 필드는 이 시간대의 offset으로 내려간다
 */
public record RefreshPolicyResponse(int refreshAfterSeconds, String timezone, AnalysisSchedule analysisSchedule) {

    /**
     * 정기 분석 배치. 화면 재조회 주기와 별개다.
     *
     * @param enabled   이 서버에서 정기 실행이 켜져 있는지(chagok.jobs.scheduling-enabled)
     * @param nextRunAt 다음 정기 접수 예정 시각. 꺼져 있으면 null
     * @param lastRun   이 센터의 가장 최근 정기 실행 작업. 없으면 null
     */
    public record AnalysisSchedule(boolean enabled, String cron, OffsetDateTime nextRunAt, LastRun lastRun) {
    }

    /** completedAt은 아직 끝나지 않았으면 null이다. 진행 상태는 GET jobs/{jobId}로도 확인할 수 있다. */
    public record LastRun(UUID jobId, String targetMonth, AnalysisJob.Status status,
                          OffsetDateTime submittedAt, OffsetDateTime completedAt) {
    }
}
