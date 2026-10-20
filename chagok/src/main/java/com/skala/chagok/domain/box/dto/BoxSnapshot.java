package com.skala.chagok.domain.box.dto;

import com.skala.chagok.domain.box.entity.BoxSpec;
import java.util.UUID;

/** 생성 시점의 규격 표시 정보. 이후 규격을 수정해도 저장 결과의 치수·이름은 유지한다. */
public record BoxSnapshot(UUID id, String code, String name, Character sizeClass,
                          Integer innerWidthMm, Integer innerDepthMm, Integer innerHeightMm) {
    public static BoxSnapshot from(BoxSpec box) {
        return box == null ? null : new BoxSnapshot(box.getId(), box.getCode(), box.getName(), box.getSizeClass(),
                box.getInnerWidthMm(), box.getInnerDepthMm(), box.getInnerHeightMm());
    }
}
