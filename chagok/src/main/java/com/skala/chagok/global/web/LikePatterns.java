package com.skala.chagok.global.web;

import java.util.Locale;

public final class LikePatterns {

    public static final char ESCAPE = '\\';

    private LikePatterns() {
    }

    /** 대소문자 무시 부분 일치용 패턴. LIKE 와일드카드는 이스케이프한다. */
    public static String containsIgnoreCase(String keyword) {
        String escaped = keyword.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
