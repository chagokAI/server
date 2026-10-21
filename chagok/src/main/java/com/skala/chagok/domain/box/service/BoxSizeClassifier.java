package com.skala.chagok.domain.box.service;

import com.skala.chagok.global.config.ChagokProperties;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

/**
 * 내경 부피로 박스 크기 분류(S/M/L)를 정한다. 규격명 형식(N421호, AN82호 …)이 제각각이라
 * 이름 대신 치수로 판정한다. 기준은 application.yaml의 chagok.box-size에서 바꿀 수 있다.
 */
@Component
@RequiredArgsConstructor
public class BoxSizeClassifier {

    private static final long MM3_PER_LITER = 1_000_000L;

    private final ChagokProperties properties;

    public char classify(int widthMm, int depthMm, int heightMm) {
        long volume = (long) widthMm * depthMm * heightMm;
        ChagokProperties.BoxSize size = properties.boxSize();
        if (volume <= size.smallMaxLiters() * MM3_PER_LITER) {
            return 'S';
        }
        if (volume <= size.mediumMaxLiters() * MM3_PER_LITER) {
            return 'M';
        }
        return 'L';
    }
}
