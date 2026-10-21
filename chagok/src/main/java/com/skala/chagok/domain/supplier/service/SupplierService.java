package com.skala.chagok.domain.supplier.service;

import com.skala.chagok.domain.supplier.dto.SupplierResponse;
import com.skala.chagok.domain.supplier.entity.Supplier;
import com.skala.chagok.domain.supplier.repository.SupplierRepository;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.web.NameOrder;

import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final WarehouseRepository warehouseRepository;

    public List<SupplierResponse> getSuppliers(UUID warehouseId, String keyword) {
        if (!warehouseRepository.existsById(warehouseId)) {
            throw BusinessException.notFound("센터");
        }
        List<Supplier> suppliers = keyword == null || keyword.isBlank()
                ? supplierRepository.findByWarehouseId(warehouseId)
                : supplierRepository.findByWarehouseIdAndNameContainingIgnoreCase(warehouseId, keyword.trim());
        return suppliers.stream()
                .sorted(NameOrder.byName(Supplier::getName, Supplier::getId))
                .map(SupplierResponse::from)
                .toList();
    }
}
