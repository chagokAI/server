package com.skala.chagok.domain.event.repository;

import com.skala.chagok.domain.event.entity.ItemEvent;
import com.skala.chagok.domain.event.entity.ItemEventId;

import java.util.UUID;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemEventRepository extends JpaRepository<ItemEvent, ItemEventId> {

    boolean existsByBoxSpecId(UUID boxSpecId);

    int countByWarehouseIdAndStatus(UUID warehouseId, String status);

    @EntityGraph(attributePaths = {"product", "boxSpec"})
    Optional<ItemEvent> findByWarehouseIdAndId_EventId(UUID warehouseId, String eventId);

    @EntityGraph(attributePaths = {"product", "boxSpec"})
    @Query("select e from ItemEvent e where e.warehouse.id = :warehouseId "
            + "order by e.id.occurredAt desc, e.id.eventId asc")
    List<ItemEvent> findRecent(UUID warehouseId, Pageable pageable);
}
