package com.skala.chagok.live;

import java.time.Clock;
import java.time.YearMonth;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.skala.chagok.domain.inventory.service.StockChangedEvent;
import com.skala.chagok.domain.job.entity.AnalysisJob;
import com.skala.chagok.domain.job.service.JobCommandService;
import com.skala.chagok.global.config.ChagokProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 라이브 초기화가 커밋된 뒤 그 센터의 이번 달·다음 달 예측을 다시 접수한다.
 *
 * <p>초기화는 라이브 관측이 섞인 예측을 지운다. 다음 정기 실행(기본 5분)까지 기다리면 그동안 최신 예측이 비거나 지운 결과의
 * 이전 값이 보인다. 커밋 전에 접수하면 초기화가 롤백돼도 작업만 남으므로 커밋 후에 접수한다. 같은 달의 활성 작업이 있으면
 * 접수 서비스가 그 작업을 돌려주어 중복 계산하지 않는다. 정기 실행과 같은 시스템 계산이라 SCHEDULED로 남긴다.
 */
@Slf4j
@Component
class LiveForecastRefresher {
    private final JobCommandService jobs;
    private final ChagokProperties properties;
    private final Clock jobClock;
    private final TransactionTemplate newTransaction;

    LiveForecastRefresher(JobCommandService jobs, ChagokProperties properties, Clock jobClock, PlatformTransactionManager transactions) {
        this.jobs = jobs; this.properties = properties; this.jobClock = jobClock;
        this.newTransaction = new TransactionTemplate(transactions);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // 커밋 후 리스너는 끝난 트랜잭션의 동기화 안에서 불린다. 접수 서비스가 그 트랜잭션에 참여하려 하면 활성 트랜잭션이 없어
    // 실패하므로(테스트에서 확인) 새 트랜잭션을 연다. 달마다 따로 연다. 한 트랜잭션으로 묶으면 한 달의 실패가 트랜잭션을
    // 롤백 전용으로 만들어 다른 달의 접수까지 함께 사라진다(테스트에서 확인).
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void refresh(StockChangedEvent event) {
        if (!event.liveReset()) return;
        YearMonth current = YearMonth.now(jobClock.withZone(properties.timezone()));
        for (YearMonth month : new YearMonth[]{current, current.plusMonths(1)}) {
            try {
                newTransaction.executeWithoutResult(status -> jobs.submit(event.warehouseId(), month.toString(),
                        AnalysisJob.Type.PROCUREMENT_AND_COST, AnalysisJob.Trigger.SCHEDULED));
            } catch (RuntimeException e) {
                log.warn("라이브 초기화 후 예측 재접수 실패: warehouseId={}, targetMonth={}", event.warehouseId(), month, e);
            }
        }
    }
}
