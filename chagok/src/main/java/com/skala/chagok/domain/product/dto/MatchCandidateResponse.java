package com.skala.chagok.domain.product.dto;

import com.skala.chagok.domain.box.dto.BoxSnapshot;
import java.math.BigDecimal;

/** 치수와 회전 가능 여부로 계산한다. AI 추론 결과로 표시하지 않는다. */
public record MatchCandidateResponse(BoxSnapshot boxSpec, BigDecimal fillRatePct,
                                     boolean recommended, boolean selected, String source) {}
