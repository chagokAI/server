package com.skala.chagok.global.web;

import java.util.Comparator;
import java.util.function.Function;

/**
 * 이름 가나다순 정렬. 로컬 DB 기본 정렬 규칙(en_US.utf8)은 한글을 가나다순으로 정렬하지 않아서
 * ("공급사"가 "가나포장"보다 앞에 옴) 개수가 적은 드롭다운 목록은 메모리에서 정렬한다.
 * 한글 음절은 유니코드 순서가 가나다순과 같다.
 */
public final class NameOrder {

    private NameOrder() {
    }

    /** 이름 가나다순, 이름이 없으면 뒤로, 같으면 tieBreaker 순 */
    public static <T, U extends Comparable<? super U>> Comparator<T> byName(Function<T, String> name,
                                                                            Function<T, U> tieBreaker) {
        return Comparator.comparing(name, Comparator.nullsLast(Comparator.<String>naturalOrder()))
                .thenComparing(tieBreaker);
    }
}
