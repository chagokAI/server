package com.skala.chagok.domain.procurement.repository;

import com.skala.chagok.domain.procurement.entity.ProcurementProposal;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcurementProposalRepository extends JpaRepository<ProcurementProposal, UUID> {

    /** 같은 달 제안서가 여러 번 생성될 수 있으므로 가장 최근 것을 쓴다. targetMonth는 YYYY-MM. */
    Optional<ProcurementProposal> findFirstByWarehouseIdAndTargetMonthOrderByGeneratedAtDesc(UUID warehouseId,
                                                                                            String targetMonth);

    Optional<ProcurementProposal> findFirstByWarehouseIdAndTargetMonthOrderByGeneratedAtDescIdDesc(
            UUID warehouseId, String targetMonth);
    Optional<ProcurementProposal> findByIdAndWarehouseId(UUID id, UUID warehouseId);

    List<ProcurementProposal> findByWarehouseIdAndTargetMonthOrderByGeneratedAtDescIdDesc(UUID warehouseId, String targetMonth);

    List<ProcurementProposal> findByWarehouseIdOrderByGeneratedAtDescIdDesc(UUID warehouseId, org.springframework.data.domain.Pageable pageable);
}
