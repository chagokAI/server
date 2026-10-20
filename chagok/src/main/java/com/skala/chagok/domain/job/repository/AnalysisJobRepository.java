package com.skala.chagok.domain.job.repository;

import com.skala.chagok.domain.job.entity.AnalysisJob;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {
    Optional<AnalysisJob> findByIdAndWarehouseId(UUID id, UUID warehouseId);
    Optional<AnalysisJob> findFirstByWarehouseIdAndTargetMonthAndTypeOrderByCreatedAtDescIdDesc(
            UUID warehouseId, String targetMonth, AnalysisJob.Type type);

    Optional<AnalysisJob> findByActiveKey(String activeKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select j from AnalysisJob j where j.status = :status order by j.createdAt, j.id")
    List<AnalysisJob> findNextLocked(AnalysisJob.Status status, Pageable pageable);
}
