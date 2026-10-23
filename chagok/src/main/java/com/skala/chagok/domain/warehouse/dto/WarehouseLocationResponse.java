package com.skala.chagok.domain.warehouse.dto;

import com.skala.chagok.domain.warehouse.entity.Warehouse;

import java.util.UUID;

/** 관리자 지도에 찍을 센터 핀. 좌표는 WGS84 위도·경도다. */
public record WarehouseLocationResponse(UUID warehouseId, String name, String address, Double latitude, Double longitude) {

    public static WarehouseLocationResponse from(Warehouse warehouse) {
        return new WarehouseLocationResponse(warehouse.getId(), warehouse.getName(), warehouse.getAddress(),
                warehouse.getLatitude(), warehouse.getLongitude());
    }
}
