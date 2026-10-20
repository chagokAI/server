package com.skala.chagok.dlanalysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DL 측정 서버 POST /measure 응답 중 1단계에서 쓰는 값.
 *
 * <p>1단계는 물건 식별까지만 하므로 상품명·치수(size)는 읽지 않는다. 치수 저장은 2단계 범위다.
 *
 * @param status known(등록 물품) 또는 unknown(처음 보는 물품)
 * @param barcode 등록 물품의 바코드. unknown이면 null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DlMeasureResult(String status, String barcode) {

    public boolean known() {
        return "known".equals(status) && barcode != null && !barcode.isBlank();
    }
}
