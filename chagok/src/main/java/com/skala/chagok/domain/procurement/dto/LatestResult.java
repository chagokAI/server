package com.skala.chagok.domain.procurement.dto;

import com.skala.chagok.domain.job.dto.JobResponse;

/** 200 응답에서 미생성 결과와 실제 0을 구분한다. 새 작업의 실패가 마지막 성공 결과를 지우지 않는다. */
public record LatestResult<T>(String targetMonth, DataStatus dataStatus, T result, JobResponse latestJob) {
    public enum DataStatus { NOT_GENERATED, AVAILABLE }
    public static <T> LatestResult<T> of(String month, T result, JobResponse job) {
        return new LatestResult<>(month, result == null ? DataStatus.NOT_GENERATED : DataStatus.AVAILABLE, result, job);
    }
}
