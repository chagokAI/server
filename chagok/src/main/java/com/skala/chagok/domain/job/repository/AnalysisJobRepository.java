package com.skala.chagok.domain.job.repository;

import com.skala.chagok.domain.job.entity.AnalysisJob;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {
    Optional<AnalysisJob> findByIdAndWarehouseId(UUID id, UUID warehouseId);
    Optional<AnalysisJob> findFirstByWarehouseIdAndTargetMonthAndTypeOrderByCreatedAtDescIdDesc(
            UUID warehouseId, String targetMonth, AnalysisJob.Type type);
}
