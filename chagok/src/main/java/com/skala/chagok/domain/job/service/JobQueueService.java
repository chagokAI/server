package com.skala.chagok.domain.job.service;

import com.skala.chagok.domain.job.config.JobProperties;
import com.skala.chagok.domain.job.entity.AnalysisJob;
import com.skala.chagok.domain.job.repository.AnalysisJobRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class JobQueueService {
    private final AnalysisJobRepository jobs;
    private final NamedParameterJdbcTemplate jdbc;
    private final JobProperties properties;
    private final Clock jobClock;

    @Transactional
    public int expireStale() {
        var now = jobClock.instant();
        return jdbc.update("""
                update analysis_job set status = 'TIMED_OUT', completed_at = :now, active_key = null,
                    failure_code = 'EXECUTION_TIMED_OUT', failure_message = '작업 실행 기한이 지났습니다.', version = version + 1
                where status = 'RUNNING' and started_at < :cutoff
                """, Map.of("now", Timestamp.from(now), "cutoff", Timestamp.from(now.minus(properties.timeout()))));
    }

    @Transactional
    public Optional<JobClaim> claimNext() {
        var rows = jobs.findNextLocked(AnalysisJob.Status.PENDING, PageRequest.of(0, 1));
        if (rows.isEmpty()) return Optional.empty();
        var job = rows.getFirst();
        job.start(jobClock.instant());
        jobs.flush();
        return Optional.of(new JobClaim(job.getId(), job.getVersion()));
    }

    @Transactional
    public void fail(JobClaim claim, String code, String message) {
        jobs.findById(claim.id()).filter(job -> job.getStatus() == AnalysisJob.Status.RUNNING
                        && Objects.equals(job.getVersion(), claim.version()))
                .ifPresent(job -> job.fail(code, message, jobClock.instant()));
    }
}
