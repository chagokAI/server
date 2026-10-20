package com.skala.chagok.domain.purchase.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 박스 주문 발주서 생성(승인) 요청.
 * 화면에서 수량을 수정할 수 있으므로 승인 시점의 품목 전체를 함께 받는다.
 *
 * @param proposalId            발주서의 재료가 된 발주 제안서 ID
 * @param items                 발주할 품목 목록 (규격별 수량·단가·금액)
 * @param totalQty              총 수량. 서버가 품목 합계와 대조해 검증한다.
 * @param totalAmountKrw        총 금액(VAT 별도). 서버가 품목 합계와 대조해 검증한다.
 * @param requestedDeliveryDate 납품 희망일
 */
public record PurchaseOrderCreateRequest(

        @NotNull(message = "발주 제안서 ID는 필수입니다.")
        UUID proposalId,

        @NotEmpty(message = "발주할 품목이 최소 1건 필요합니다.")
        @Valid
        List<Item> items,

        @NotNull(message = "총 수량은 필수입니다.")
        @Min(value = 1, message = "총 수량은 1 이상이어야 합니다.")
        Integer totalQty,

        @NotNull(message = "총 금액은 필수입니다.")
        @Min(value = 0, message = "총 금액은 0 이상이어야 합니다.")
        Long totalAmountKrw,

        @NotNull(message = "납품 희망일은 필수입니다.")
        @FutureOrPresent(message = "납품 희망일은 오늘 이후여야 합니다.")
        LocalDate requestedDeliveryDate
) {

    /**
     * @param boxSpecId    박스 규격 ID
     * @param qty          수량
     * @param unitPriceKrw 단가
     * @param amountKrw    금액. 서버가 {@code qty * unitPriceKrw}와 대조해 검증한다.
     */
    public record Item(

            @NotNull(message = "박스 규격 ID는 필수입니다.")
            UUID boxSpecId,

            @NotNull(message = "수량은 필수입니다.")
            @Min(value = 1, message = "수량은 1 이상이어야 합니다.")
            Integer qty,

            @NotNull(message = "단가는 필수입니다.")
            @Min(value = 0, message = "단가는 0 이상이어야 합니다.")
            Integer unitPriceKrw,

            @NotNull(message = "금액은 필수입니다.")
            @Min(value = 0, message = "금액은 0 이상이어야 합니다.")
            Long amountKrw
    ) {
    }
}
