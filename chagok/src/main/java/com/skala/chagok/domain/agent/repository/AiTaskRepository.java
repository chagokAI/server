package com.skala.chagok.domain.agent.repository;

import com.skala.chagok.domain.agent.entity.AiTask;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiTaskRepository extends JpaRepository<AiTask, UUID> {
    Optional<AiTask> findByIdAndWarehouseId(UUID id, UUID warehouseId);
    Optional<AiTask> findFirstByWarehouseIdAndTargetMonthAndStatusInOrderByCreatedAtDesc(
            UUID warehouseId, String targetMonth, Collection<AiTask.Status> statuses);
    List<AiTask> findByWarehouseIdOrderByCreatedAtDesc(UUID warehouseId, Pageable pageable);
    List<AiTask> findByWarehouseIdAndRequestedByIdOrderByCreatedAtDesc(UUID warehouseId, UUID userId, Pageable pageable);
}
