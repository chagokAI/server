package com.skala.chagok.domain.procurement.repository;

import com.skala.chagok.domain.procurement.entity.CostForecast;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CostForecastRepository extends JpaRepository<CostForecast, UUID> {
    Optional<CostForecast> findFirstByWarehouseIdAndTargetMonthOrderByGeneratedAtDescIdDesc(UUID warehouseId, String targetMonth);
}
