package com.skala.chagok.domain.box.service;

import com.skala.chagok.domain.box.entity.BoxSpec;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.data.domain.Sort;

/**
 * 박스 규격 목록(06)과 재고 목록(05)이 함께 쓰는 메모리 정렬.
 *
 * <p>박스 코드는 화면 순서대로 S → M → L, 같은 분류 안에서는 번호를 숫자로 비교한다(L-4 &lt; L-10).
 * 문자열 정렬이면 L → M → S가 되어 화면과 맞지 않아 SQL 대신 여기서 정렬한다.
 */
public final class BoxSpecOrdering {

    private static final List<Character> SIZE_CLASS_ORDER = List.of('S', 'M', 'L');
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d+)$");

    /** S → M → L → 그 외 분류, 번호 숫자순, 그래도 같으면 코드 문자열순 */
    public static final Comparator<BoxSpec> BOX_CODE = Comparator
            .comparingInt(BoxSpecOrdering::sizeClassRank)
            .thenComparingLong(BoxSpecOrdering::codeNumber)
            .thenComparing(BoxSpec::getCode, Comparator.nullsLast(Comparator.naturalOrder()));

    private BoxSpecOrdering() {
    }

    /**
     * PageRequests가 검증한 Sort를 Comparator로 바꾼다. 속성명은 BoxSpec 필드명이다.
     * DB 정렬과 같게 NULL은 방향과 관계없이 항상 뒤로 보낸다.
     */
    public static Comparator<BoxSpec> of(Sort sort) {
        Comparator<BoxSpec> result = null;
        for (Sort.Order order : sort) {
            Comparator<BoxSpec> next = switch (order.getProperty()) {
                case "code" -> order.isAscending() ? BOX_CODE : BOX_CODE.reversed();
                case "onHandQty" -> by(BoxSpec::getOnHandQty, order);
                case "daysToStockout" -> by(BoxSpec::getDaysToStockout, order);
                case "id" -> by(BoxSpec::getId, order);
                default -> throw new IllegalStateException("정렬 매핑 누락: " + order.getProperty());
            };
            result = result == null ? next : result.thenComparing(next);
        }
        return result;
    }

    private static <T extends Comparable<? super T>> Comparator<BoxSpec> by(Function<BoxSpec, T> key,
                                                                            Sort.Order order) {
        Comparator<T> natural = order.isAscending() ? Comparator.naturalOrder() : Comparator.reverseOrder();
        return Comparator.comparing(key, Comparator.nullsLast(natural));
    }

    private static int sizeClassRank(BoxSpec spec) {
        int rank = spec.getSizeClass() == null ? -1 : SIZE_CLASS_ORDER.indexOf(spec.getSizeClass());
        return rank < 0 ? SIZE_CLASS_ORDER.size() : rank;
    }

    private static long codeNumber(BoxSpec spec) {
        if (spec.getCode() == null) {
            return Long.MAX_VALUE;
        }
        Matcher matcher = TRAILING_NUMBER.matcher(spec.getCode());
        return matcher.find() ? Long.parseLong(matcher.group(1)) : Long.MAX_VALUE;
    }
}
