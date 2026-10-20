package com.skala.chagok.domain.box.dto;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.supplier.entity.Supplier;

import java.util.UUID;

/**
 * 박스 규격 정보. 재고 수량은 재고 API(INV-API)에서 제공한다.
 */
public record BoxSpecResponse(
        UUID id,
        String code,
        String name,
        Character sizeClass,
        Integer innerWidthMm,
        Integer innerDepthMm,
        Integer innerHeightMm,
        String material,
        SupplierSummary supplier,
        Integer unitPriceKrw,
        Boolean isCustom,
        Boolean isActive,
        String description,
        Integer version,
        long linkedProductCount
) {

    public record SupplierSummary(UUID id, String name) {

        static SupplierSummary from(Supplier supplier) {
            return supplier == null ? null : new SupplierSummary(supplier.getId(), supplier.getName());
        }
    }

    public static BoxSpecResponse from(BoxSpec boxSpec) {
        return from(boxSpec, 0);
    }

    public static BoxSpecResponse from(BoxSpec boxSpec, long linkedProductCount) {
        return new BoxSpecResponse(
                boxSpec.getId(),
                boxSpec.getCode(),
                boxSpec.getName(),
                boxSpec.getSizeClass(),
                boxSpec.getInnerWidthMm(),
                boxSpec.getInnerDepthMm(),
                boxSpec.getInnerHeightMm(),
                boxSpec.getMaterial(),
                SupplierSummary.from(boxSpec.getSupplier()),
                boxSpec.getUnitPriceKrw(),
                boxSpec.getIsCustom(),
                boxSpec.getIsActive(),
                boxSpec.getDescription(),
                boxSpec.getVersion(),
                linkedProductCount
        );
    }
}
