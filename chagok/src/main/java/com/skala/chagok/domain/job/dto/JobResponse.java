package com.skala.chagok.domain.job.dto;

import com.skala.chagok.domain.job.entity.AnalysisJob;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record JobResponse(UUID id, String targetMonth, AnalysisJob.Type type, AnalysisJob.Status status,
                          Instant createdAt, Instant startedAt, Instant completedAt, Instant dataAsOf,
                          UUID proposalId, UUID costForecastId, Failure failure, boolean retryable, Integer version,
                          AnalysisJob.Trigger trigger, UUID retryOfJobId, UUID retryJobId) {
    public record Failure(String code, String message) {}

    public static JobResponse from(AnalysisJob job) {
        boolean retryable = Set.of(AnalysisJob.Status.FAILED, AnalysisJob.Status.PUBLISH_FAILED,
                AnalysisJob.Status.TIMED_OUT).contains(job.getStatus()) && job.getRetryJobId() == null;
        return new JobResponse(job.getId(), job.getTargetMonth(), job.getType(), job.getStatus(), job.getCreatedAt(),
                job.getStartedAt(), job.getCompletedAt(), job.getDataAsOf(), job.getProposalId(), job.getCostForecastId(),
                job.getFailureCode() == null ? null : new Failure(job.getFailureCode(), job.getFailureMessage()),
                retryable, job.getVersion(), job.getTrigger(), job.getRetryOfJobId(), job.getRetryJobId());
    }
}
