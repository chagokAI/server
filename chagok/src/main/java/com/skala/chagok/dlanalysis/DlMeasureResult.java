package com.skala.chagok.dlanalysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DL 측정 서버 POST /measure 응답.
 *
 * <p>1단계 업무 반영은 status와 barcode만 쓴다. size는 item_event에 저장하지 않고(치수 저장은 2단계)
 * 측정 결과 기록({@link DlMeasurementLog})에만 남긴다.
 *
 * @param status known(등록 물품) 또는 unknown(처음 보는 물품)
 * @param barcode 등록 물품의 바코드. unknown이면 null
 * @param size known이면 등록 상품의 라벨 실측값(type=measured), unknown이면 모델 추정값(type=estimated)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DlMeasureResult(String status, String barcode, Size size) {

    public boolean known() {
        return "known".equals(status) && barcode != null && !barcode.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Size(
            String type,
            @JsonProperty("w_cm") Double widthCm,
            @JsonProperty("d_cm") Double depthCm,
            @JsonProperty("h_cm") Double heightCm,
            @JsonProperty("volume_cm3") Double volumeCm3) {
    }
}
