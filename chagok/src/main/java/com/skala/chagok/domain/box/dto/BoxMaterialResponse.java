package com.skala.chagok.domain.box.dto;

import com.skala.chagok.domain.box.entity.BoxMaterial;

/** 재질 드롭다운 선택지. code를 요청에 보내고 label을 화면에 보여준다. */
public record BoxMaterialResponse(String code, String label) {

    public static BoxMaterialResponse from(BoxMaterial material) {
        return new BoxMaterialResponse(material.name(), material.getLabel());
    }
}
