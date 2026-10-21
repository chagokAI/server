package com.skala.chagok.domain.box.controller;

import com.skala.chagok.domain.box.dto.BoxMaterialResponse;
import com.skala.chagok.domain.box.entity.BoxMaterial;
import com.skala.chagok.global.response.ApiResponse;
import com.skala.chagok.global.response.ApiResponses;

import java.util.Arrays;
import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class BoxMaterialController {

    private final ApiResponses apiResponses;

    /** 규격 추가·수정 모달의 재질 드롭다운. 센터와 무관한 고정 목록이다. */
    @GetMapping("/api/v1/box-materials")
    public ApiResponse<List<BoxMaterialResponse>> getBoxMaterials() {
        return apiResponses.of(Arrays.stream(BoxMaterial.values()).map(BoxMaterialResponse::from).toList());
    }
}
