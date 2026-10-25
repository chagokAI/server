package com.skala.chagok.domain.procurement.dto;

import java.math.BigDecimal;
import java.util.*;

/** 에이전트가 저장한 원본 숫자를 읽는다. 표시 문자열에서 수량·금액을 역산하지 않는다. */
public final class AgentProposalData {
    private AgentProposalData() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    public static List<Map<String, Object>> rows(Object value) {
        return value instanceof List<?> list ? list.stream().filter(Map.class::isInstance).map(AgentProposalData::object).toList() : List.of();
    }

    public static Long number(Object value) {
        if (!(value instanceof Number)) return null;
        try { return new BigDecimal(value.toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException ignored) { return null; }
    }

    public static Integer integer(Object value) {
        Long number = number(value);
        return number == null || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE ? null : number.intValue();
    }

    public static int[] dimensions(Object value) {
        if (!(value instanceof List<?> list) || list.size() != 3) return null;
        int[] result = new int[3];
        for (int i = 0; i < 3; i++) {
            Integer dimension = integer(list.get(i));
            if (dimension == null || dimension <= 0) return null;
            result[i] = dimension;
        }
        return result;
    }

    public static boolean approved(Map<String, Object> summary) {
        return "APPROVED".equals(object(summary.get("approval")).get("status"));
    }

    public static List<Map<String, Object>> pendingLines(Map<String, Object> summary, Set<String> storedCodes) {
        if (!approved(summary)) return List.of();
        return rows(summary.get("lines")).stream()
                .filter(line -> ("NEW_SPEC".equals(line.get("type")) || "CUSTOM_SPEC".equals(line.get("type")))
                        && line.get("boxSpecId") == null)
                .filter(line -> line.get("boxCode") instanceof String code && !storedCodes.contains(code)).toList();
    }
}
