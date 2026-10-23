package com.skala.chagok.dlanalysis;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.event.entity.ItemEvent;
import com.skala.chagok.domain.event.entity.ItemEventId;
import com.skala.chagok.domain.product.entity.Product;

/**
 * DL 분석 흐름이 item_event에 하는 일만 모은 저장소. 엔티티는 DB 담당 소유이므로 여기서는 쿼리만 정의한다.
 *
 * <p>행은 event_id만으로 찾는다(팀 합의). 결과 반영은 status가 PENDING일 때만 바꾸는 조건부 갱신이다.
 * Kafka가 같은 메시지를 다시 보내도 이미 끝난 작업은 갱신 건수 0으로 걸러진다.
 */
interface DlAnalysisItemEventRepository extends Repository<ItemEvent, ItemEventId> {

    ItemEvent save(ItemEvent itemEvent);

    @Query("select e.status as status, e.warehouse.id as warehouseId from ItemEvent e where e.id.eventId = :eventId")
    Optional<Snapshot> findSnapshot(@Param("eventId") String eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from ItemEvent e where e.id.eventId = :eventId")
    Optional<ItemEvent> findForUpdate(@Param("eventId") String eventId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ItemEvent e
               set e.product = :product, e.boxSpec = :boxSpec, e.fillRatePct = :fillRatePct, e.status = :to,
                   e.failureReason = null, e.failureMessage = null
             where e.id.eventId = :eventId and e.status in (:from, 'WAITING')
            """)
    int completeIfInStatus(
            @Param("eventId") String eventId,
            @Param("product") Product product,
            @Param("boxSpec") BoxSpec boxSpec,
            @Param("fillRatePct") BigDecimal fillRatePct,
            @Param("from") String from,
            @Param("to") String to);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ItemEvent e set e.status = :to where e.id.eventId = :eventId and e.status = :from")
    int changeStatusIfInStatus(@Param("eventId") String eventId, @Param("from") String from, @Param("to") String to);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ItemEvent e set e.status = 'FAILED', e.failureReason = :reason, e.failureMessage = :detail "
            + "where e.id.eventId = :eventId and e.status = 'PENDING'")
    int failWithReason(@Param("eventId") String eventId, @Param("reason") String reason, @Param("detail") String detail);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ItemEvent e set e.status = 'WAITING', e.product = :product, e.boxSpec = null, "
            + "e.fillRatePct = null, e.failureReason = :reason, e.failureMessage = :detail "
            + "where e.id.eventId = :eventId and e.status in ('PENDING', 'WAITING')")
    int waitForPacking(@Param("eventId") String eventId, @Param("product") Product product,
            @Param("reason") String reason, @Param("detail") String detail);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ItemEvent e set e.measurement = :measurement where e.id.eventId = :eventId "
            + "and e.status <> 'PENDING' and e.measurement is null")
    int recordMeasurement(@Param("eventId") String eventId, @Param("measurement") Map<String, Object> measurement);

    default int markSucceeded(String eventId, Product product, BoxSpec boxSpec, BigDecimal fillRatePct) {
        return completeIfInStatus(eventId, product, boxSpec, fillRatePct,
                ItemEventStatus.PENDING.name(), ItemEventStatus.SUCCEEDED.name());
    }

    default int markFailed(String eventId) {
        return changeStatusIfInStatus(eventId, ItemEventStatus.PENDING.name(), ItemEventStatus.FAILED.name());
    }

    interface Snapshot {
        String getStatus();

        UUID getWarehouseId();
    }
}
