package com.skala.chagok.domain.box.entity;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 박스 재질(골판지 골 종류). box_spec.material에는 코드(name())를 저장한다.
 * 크롤링 원본 데이터는 "골판지 B골" 형식이라 조회할 때는 두 형식을 모두 읽는다.
 */
public enum BoxMaterial {
    A("골판지 A골"),
    B("골판지 B골"),
    E("골판지 E골"),
    AB("골판지 AB골"),
    BA("골판지 BA골"),
    BB("골판지 BB골");

    private static final Pattern LEGACY_LABEL = Pattern.compile("([A-Za-z]+)골");

    private final String label;

    BoxMaterial(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 저장값을 해석한다. 코드("B")와 원본 표기("골판지 B골")를 모두 받고, 알 수 없으면 empty. */
    public static Optional<BoxMaterial> parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return Optional.empty();
        }
        String value = stored.trim();
        Matcher legacy = LEGACY_LABEL.matcher(value);
        String code = legacy.find() ? legacy.group(1) : value;
        try {
            return Optional.of(valueOf(code.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
