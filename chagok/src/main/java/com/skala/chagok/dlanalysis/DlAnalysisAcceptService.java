package com.skala.chagok.dlanalysis;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.skala.chagok.domain.event.entity.ItemEvent;
import com.skala.chagok.domain.event.entity.ItemEventId;
import com.skala.chagok.domain.warehouse.entity.Warehouse;

import jakarta.persistence.EntityManager;

/**
 * 촬영 1건을 DL 분석 작업으로 접수한다.
 *
 * <p>outbox 없이 정합성을 맞추려고 「발행 확인 → 저장」 순서를 쓴다. 발행이 실패하면 아무것도 저장하지 않고
 * {@link DlAnalysisRequestPublishException}을 그대로 올린다(요청 API는 503으로 응답). 저장이 실패하면 메시지만
 * 남는다. DL Worker는 그 요청도 분석하지만, 결과 컨슈머가 행을 찾지 못해 고아 결과로 버린다. 발행은 트랜잭션 밖에서 기다리고, 저장은
 * 저장소의 save가 여는 짧은 트랜잭션 하나로 끝난다.
 *
 * <p>HTTP 진입점은 팀 합의 전이라 아직 없다. 진입점이 정해지면 그 컨트롤러가 이 서비스를 부른다.
 */
@Service
@KafkaIntegrationEnabled
public class DlAnalysisAcceptService {

    private final DlAnalysisRequestProducer producer;
    private final DlAnalysisItemEventRepository itemEvents;
    private final EntityManager entityManager;

    DlAnalysisAcceptService(
            DlAnalysisRequestProducer producer,
            DlAnalysisItemEventRepository itemEvents,
            EntityManager entityManager) {
        this.producer = producer;
        this.itemEvents = itemEvents;
        this.entityManager = entityManager;
    }

    /**
     * 지금 촬영한 1건을 접수한다. 촬영 시각은 접수 시각으로 기록한다.
     *
     * @param imageRefs cam1, cam2, cam3 순서의 이미지 경로
     * @return 접수한 작업의 eventId
     * @throws IllegalArgumentException 센터가 없거나 이미지가 3장이 아닐 때. 이때는 발행도 하지 않는다
     * @throws DlAnalysisRequestPublishException 발행 확인을 받지 못했을 때. 아무것도 저장되지 않는다
     */
    public String accept(UUID warehouseId, List<String> imageRefs) {
        return accept(warehouseId, imageRefs, Instant.now());
    }

    /**
     * 촬영 시각을 지정해 접수한다. 과거 촬영분을 일괄로 넣을 때 쓴다(시계열 시험용 일괄 접수 포함).
     *
     * @param occurredAt item_event.occurred_at에 기록할 촬영 시각
     */
    public String accept(UUID warehouseId, List<String> imageRefs, Instant occurredAt) {
        if (occurredAt == null) {
            throw new IllegalArgumentException("촬영 시각이 없다");
        }
        if (warehouseId == null || entityManager.find(Warehouse.class, warehouseId) == null) {
            throw new IllegalArgumentException("센터가 없다: " + warehouseId);
        }
        String eventId = UUID.randomUUID().toString();
        DlAnalysisRequest request = DlAnalysisRequest.of(eventId, warehouseId.toString(), imageRefs);

        producer.publish(request);

        itemEvents.save(ItemEvent.builder()
                .id(new ItemEventId(eventId, occurredAt))
                .warehouse(entityManager.getReference(Warehouse.class, warehouseId))
                .status(ItemEventStatus.PENDING.name())
                .build());
        return eventId;
    }
}
