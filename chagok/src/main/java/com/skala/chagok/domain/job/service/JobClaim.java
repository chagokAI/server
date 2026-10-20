package com.skala.chagok.domain.job.service;

import java.util.UUID;

/** 실행 시도별 version을 포함해 기한 초과·재시도 이전 작업의 결과 반영을 막는다. */
public record JobClaim(UUID id, int version) {}
