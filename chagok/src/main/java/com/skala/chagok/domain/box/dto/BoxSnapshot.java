package com.skala.chagok.domain.box.dto;

import com.skala.chagok.domain.box.entity.BoxSpec;
import java.util.UUID;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** 생성 시점의 규격 표시 정보. 이후 규격을 수정해도 저장 결과의 치수·이름은 유지한다. */
public record BoxSnapshot(UUID id, String code, String name, Character sizeClass,
                          Integer innerWidthMm, Integer innerDepthMm, Integer innerHeightMm) {
    private static final Pattern NUMBER = Pattern.compile("(\\d+)$");
    public static final Comparator<BoxSnapshot> ORDER = Comparator.comparingInt(BoxSnapshot::sizeRank)
            .thenComparingLong(BoxSnapshot::number).thenComparing(BoxSnapshot::code, Comparator.nullsLast(Comparator.naturalOrder()));

    private int sizeRank() {
        int rank = sizeClass == null ? -1 : List.of('S', 'M', 'L').indexOf(sizeClass);
        return rank < 0 ? 3 : rank;
    }

    private long number() {
        var match = NUMBER.matcher(code == null ? "" : code);
        try { return match.find() ? Long.parseLong(match.group(1)) : Long.MAX_VALUE; }
        catch (NumberFormatException e) { return Long.MAX_VALUE; }
    }

    public static BoxSnapshot from(BoxSpec box) {
        return box == null ? null : new BoxSnapshot(box.getId(), box.getCode(), box.getName(), box.getSizeClass(),
                box.getInnerWidthMm(), box.getInnerDepthMm(), box.getInnerHeightMm());
    }
}
