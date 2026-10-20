package com.skala.chagok.global.web;

import com.skala.chagok.global.error.BusinessException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

public final class QueryValues {
    private QueryValues() {}

    public static YearMonth month(String value, YearMonth defaultValue) {
        if (value == null) return defaultValue;
        try {
            if (!value.matches("\\d{4}-\\d{2}")) throw new DateTimeParseException("", value, 0);
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            throw BusinessException.invalidField("targetMonth", "YYYY-MM 형식의 유효한 월이어야 합니다.");
        }
    }

    public static void dateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) throw BusinessException.invalidField("to", "from 이후 날짜여야 합니다.");
    }

}
