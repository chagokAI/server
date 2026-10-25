package com.skala.chagok.domain.purchase.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.skala.chagok.domain.box.dto.BoxSnapshot;
import com.skala.chagok.domain.purchase.dto.response.PurchaseOrderResponse;
import com.skala.chagok.domain.purchase.entity.PurchaseOrderStatus;
import com.skala.chagok.global.config.ChagokProperties;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class PurchaseOrderPdfRendererTest {

    private static final ChagokProperties PROPERTIES = new ChagokProperties(ZoneId.of("Asia/Seoul"), 300,
            new ChagokProperties.Inventory(3, 14), new ChagokProperties.BoxSize(10, 50));

    private static PurchaseOrderResponse order(List<PurchaseOrderResponse.Item> items) {
        return new PurchaseOrderResponse(UUID.randomUUID(), "PO-2026-0007", null, PurchaseOrderStatus.APPROVED,
                Instant.parse("2026-10-20T01:00:00Z"), null, Instant.parse("2026-10-21T02:00:00Z"), null,
                LocalDate.of(2026, 10, 30),
                new PurchaseOrderResponse.SupplierInfo(UUID.randomUUID(), "그린팩코리아", "월말 결제", (short) 7),
                new PurchaseOrderResponse.UserInfo(UUID.randomUUID(), "신혜", "물류팀", "센터장"),
                new PurchaseOrderResponse.UserInfo(UUID.randomUUID(), "김승인", "구매팀", "팀장"),
                new PurchaseOrderResponse.Recipient(UUID.randomUUID(), "이천 물류센터", "경기도 이천시 어디로 12",
                        "09:00~18:00", "031-000-0000"),
                null, PurchaseOrderResponse.totalQty(items), PurchaseOrderResponse.totalAmount(items), "KRW", false,
                0, items);
    }

    private static PurchaseOrderResponse.Item item(String code, int qty, int unitPrice) {
        var box = new BoxSnapshot(UUID.randomUUID(), code, "중형", 'M', 300, 200, 150);
        return new PurchaseOrderResponse.Item(box, qty, unitPrice, (long) qty * unitPrice, true);
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (var document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    @Test void writesKoreanOrderDetailsIntoASinglePageDocument() throws Exception {
        byte[] pdf = new PurchaseOrderPdfRenderer(PROPERTIES).render(order(List.of(item("4호", 100, 180))));

        assertThat(pdf).startsWith("%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        String text = textOf(pdf);
        assertThat(text).contains("발주서").contains("PO-2026-0007")
                .contains("그린팩코리아").contains("월말 결제")
                // 발주일은 저장된 UTC가 아니라 센터 시간대로 적는다.
                .contains("2026.10.20").contains("2026.10.30")
                .contains("신혜 · 센터장 · 물류팀").contains("승인 완료")
                .contains("이천 물류센터").contains("경기도 이천시 어디로 12").contains("031-000-0000")
                .contains("4호").contains("300 x 200 x 150")
                .contains("100개").contains("180원").contains("18,000원")
                .contains("합계 (VAT 별도)");
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test void missingValuesBecomeDashesInsteadOfFailingTheDownload() throws Exception {
        var items = List.of(new PurchaseOrderResponse.Item(null, null, null, null, false));
        var order = new PurchaseOrderResponse(UUID.randomUUID(), "PO-2026-0008", null, PurchaseOrderStatus.PENDING,
                null, null, null, null, null, null, null, null, null, null,
                PurchaseOrderResponse.totalQty(items), PurchaseOrderResponse.totalAmount(items), "KRW", false, 0, items);

        byte[] pdf = new PurchaseOrderPdfRenderer(PROPERTIES).render(order);

        assertThat(textOf(pdf)).contains("PO-2026-0008").contains("미지정").contains("승인 대기");
    }

    @Test void manyItemsSpillOntoFurtherPagesAndRepeatTheTableHeader() throws Exception {
        var items = IntStream.rangeClosed(1, 40).mapToObj(i -> item(i + "호", i * 10, 200)).toList();

        byte[] pdf = new PurchaseOrderPdfRenderer(PROPERTIES).render(order(items));

        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
        }
        String text = textOf(pdf);
        assertThat(text).contains("40호");
        // 쪽마다 표 머리를 다시 그린다.
        assertThat(text.split("사이즈 \\(mm\\)", -1).length - 1).isGreaterThan(1);
    }
}
