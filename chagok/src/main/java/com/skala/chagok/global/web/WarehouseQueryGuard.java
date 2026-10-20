package com.skala.chagok.global.web;

import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import com.skala.chagok.global.error.BusinessException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WarehouseQueryGuard {
    private final WarehouseRepository warehouses;

    public void require(UUID warehouseId) {
        if (!warehouses.existsById(warehouseId)) throw BusinessException.notFound("센터");
    }
}
