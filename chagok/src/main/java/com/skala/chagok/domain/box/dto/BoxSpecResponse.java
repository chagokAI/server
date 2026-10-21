package com.skala.chagok.domain.box.dto;

import com.skala.chagok.domain.box.entity.BoxMaterial;
import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.supplier.entity.Supplier;

import java.util.Optional;
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
        String materialLabel,
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
        // 크롤링 원본("골판지 B골")처럼 코드가 아닌 값도 코드·표시 이름으로 바꿔 내려준다. 해석할 수 없으면 원본 그대로.
        Optional<BoxMaterial> material = BoxMaterial.parse(boxSpec.getMaterial());
        return new BoxSpecResponse(
                boxSpec.getId(),
                boxSpec.getCode(),
                boxSpec.getName(),
                boxSpec.getSizeClass(),
                boxSpec.getInnerWidthMm(),
                boxSpec.getInnerDepthMm(),
                boxSpec.getInnerHeightMm(),
                material.map(BoxMaterial::name).orElse(boxSpec.getMaterial()),
                material.map(BoxMaterial::getLabel).orElse(boxSpec.getMaterial()),
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
