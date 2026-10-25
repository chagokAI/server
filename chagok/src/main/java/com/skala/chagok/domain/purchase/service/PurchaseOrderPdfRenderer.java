package com.skala.chagok.domain.purchase.service;

import com.skala.chagok.domain.purchase.dto.response.PurchaseOrderResponse;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.error.ErrorCode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 발주서 PDF를 그린다. 공급사에 보내는 문서라 화면 표시가 아니라 저장된 값(규격 스냅숏·저장 단가)을 그대로 적는다.
 *
 * <p>HTML→PDF 변환기는 유지보수가 끊긴 것뿐이라 PDFBox로 직접 그린다. 한글은 OS 폰트에 기대지 않고
 * 번들한 나눔고딕(OFL)을 임베드한다. PDFBox가 사용한 글자만 서브셋으로 넣으므로 결과 파일은 작다.
 */
@Component
public class PurchaseOrderPdfRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");
    private static final String DASH = "—";

    private static final float MARGIN = 50;
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2;
    private static final float ROW_HEIGHT = 22;
    private static final float BOTTOM_LIMIT = MARGIN + 80;

    /** 규격 · 사이즈 · 수량 · 단가 · 금액 */
    private static final float[] COLUMNS = {70, 150, 90, 95, 90};
    private static final String[] HEADERS = {"규격", "사이즈 (mm)", "수량", "단가", "금액"};

    private static final PDColor TEXT = rgb(26, 26, 26);
    private static final PDColor MUTED = rgb(122, 122, 122);
    private static final PDColor LINE = rgb(222, 222, 222);
    private static final PDColor BAND = rgb(247, 244, 239);

    private final byte[] regularFont;
    private final byte[] boldFont;
    private final ChagokProperties properties;

    public PurchaseOrderPdfRenderer(ChagokProperties properties) throws IOException {
        this.properties = properties;
        this.regularFont = read("fonts/NanumGothic-Regular.ttf");
        this.boldFont = read("fonts/NanumGothic-Bold.ttf");
    }

    /** 발주서 한 건을 A4 PDF 바이트로 만든다. 품목이 많으면 표 머리를 반복하며 쪽을 넘긴다. */
    public byte[] render(PurchaseOrderResponse order) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType0Font regular = PDType0Font.load(document, new java.io.ByteArrayInputStream(regularFont), true);
            PDType0Font bold = PDType0Font.load(document, new java.io.ByteArrayInputStream(boldFont), true);
            new Sheet(document, regular, bold, order).draw();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "발주서 PDF를 만들지 못했습니다.");
        }
    }

    /** 한 발주서를 그리는 동안의 상태(현재 쪽·커서)를 들고 있는다. */
    private final class Sheet {
        private final PDDocument document;
        private final PDType0Font regular;
        private final PDType0Font bold;
        private final PurchaseOrderResponse order;
        private PDPageContentStream content;
        private float y;

        private Sheet(PDDocument document, PDType0Font regular, PDType0Font bold, PurchaseOrderResponse order) {
            this.document = document;
            this.regular = regular;
            this.bold = bold;
            this.order = order;
        }

        private void draw() throws IOException {
            newPage();
            title();
            infoBlock();
            recipientBlock();
            itemTable();
            totals();
            footer();
            content.close();
        }

        private void newPage() throws IOException {
            if (content != null) {
                content.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            y = PAGE_HEIGHT - MARGIN;
        }

        private void title() throws IOException {
            text("발주서", bold, 22, MARGIN, y - 22, TEXT);
            String poNo = value(order.poNo());
            text(poNo, bold, 12, MARGIN + CONTENT_WIDTH - width(poNo, bold, 12), y - 14, TEXT);
            String issued = "발주일 " + date(order.createdAt());
            text(issued, regular, 9, MARGIN + CONTENT_WIDTH - width(issued, regular, 9), y - 28, MUTED);
            y -= 44;
            line(y);
            y -= 20;
        }

        private void infoBlock() throws IOException {
            var supplier = order.supplier();
            List<String[]> fields = List.of(
                    new String[]{"공급사", supplier == null ? "미지정" : value(supplier.name())},
                    new String[]{"결제 조건", supplier == null ? DASH : value(supplier.paymentTerms())},
                    new String[]{"납품 예정일", date(order.deliveryDate())},
                    new String[]{"요청자", person(order.requester())},
                    new String[]{"승인자", person(order.approver())},
                    new String[]{"진행 상태", status()});

            float columnWidth = CONTENT_WIDTH / 3;
            float top = y;
            for (int i = 0; i < fields.size(); i++) {
                float x = MARGIN + columnWidth * (i % 3);
                float rowTop = top - (i / 3) * 34;
                text(fields.get(i)[0], regular, 8, x, rowTop - 9, MUTED);
                text(fields.get(i)[1], bold, 11, x, rowTop - 24, TEXT);
            }
            y = top - 34 * 2 - 6;
        }

        private void recipientBlock() throws IOException {
            var recipient = order.recipient();
            if (recipient == null) {
                return;
            }
            band(y - 56, 56);
            text("납품지", regular, 8, MARGIN + 12, y - 16, MUTED);
            text(value(recipient.name()), bold, 11, MARGIN + 12, y - 31, TEXT);
            text(value(recipient.address()), regular, 9, MARGIN + 12, y - 46, TEXT);
            String hours = "입고 가능 " + value(recipient.receivingHours());
            String contact = value(recipient.receivingContact());
            text(hours, regular, 9, MARGIN + CONTENT_WIDTH - 12 - width(hours, regular, 9), y - 31, MUTED);
            text(contact, regular, 9, MARGIN + CONTENT_WIDTH - 12 - width(contact, regular, 9), y - 46, MUTED);
            y -= 76;
        }

        private void itemTable() throws IOException {
            tableHeader();
            for (var item : order.items()) {
                if (y < BOTTOM_LIMIT) {
                    newPage();
                    tableHeader();
                }
                var box = item.boxSpec();
                String[] cells = {
                        box == null ? DASH : value(box.code()),
                        box == null ? DASH : dimensions(box.innerWidthMm(), box.innerDepthMm(), box.innerHeightMm()),
                        quantity(item.qty()),
                        money(item.unitPriceKrw() == null ? null : item.unitPriceKrw().longValue()),
                        money(item.amountKrw()),
                };
                row(cells, regular, false);
            }
            // 품목이 없으면 표 머리만 남으므로 아래 선을 직접 긋는다. 있으면 마지막 행이 이미 그었다.
            if (order.items().isEmpty()) {
                line(y);
            }
        }

        private void tableHeader() throws IOException {
            band(y - ROW_HEIGHT, ROW_HEIGHT);
            row(HEADERS, bold, true);
        }

        /** 규격·사이즈는 왼쪽, 수량·단가·금액은 오른쪽 정렬 */
        private void row(String[] cells, PDType0Font font, boolean header) throws IOException {
            float x = MARGIN;
            for (int i = 0; i < cells.length; i++) {
                float textY = y - ROW_HEIGHT + 7;
                if (i < 2) {
                    text(cells[i], font, 9, x + 8, textY, TEXT);
                } else {
                    text(cells[i], font, 9, x + COLUMNS[i] - 8 - width(cells[i], font, 9), textY, TEXT);
                }
                x += COLUMNS[i];
            }
            y -= ROW_HEIGHT;
            if (!header) {
                line(y);
            }
        }

        private void totals() throws IOException {
            if (y < BOTTOM_LIMIT) {
                newPage();
            }
            y -= 10;
            band(y - 40, 40);
            text("총 수량 " + quantity(order.totalQty()), bold, 11, MARGIN + 12, y - 25, TEXT);
            String label = order.vatIncluded() ? "합계 (VAT 포함)" : "합계 (VAT 별도)";
            String total = money(order.totalAmountKrw());
            float right = MARGIN + CONTENT_WIDTH - 12;
            text(total, bold, 15, right - width(total, bold, 15), y - 27, TEXT);
            text(label, regular, 9, right - width(total, bold, 15) - 10 - width(label, regular, 9), y - 25, MUTED);
            y -= 52;
        }

        private void footer() throws IOException {
            String note = "발행 " + dateTime(Instant.now()) + " · 차곡(CHAGOK) 발주 관리";
            text(note, regular, 8, MARGIN, MARGIN - 10, MUTED);
        }

        private String status() {
            return switch (order.status()) {
                case PENDING -> "승인 대기";
                case APPROVED -> "승인 완료";
                case IN_TRANSIT -> "배송 중";
                case RECEIVED -> "입고 완료";
                case REJECTED -> "반려";
            };
        }

        private void text(String value, PDType0Font font, float size, float x, float baseline, PDColor color)
                throws IOException {
            content.beginText();
            content.setFont(font, size);
            content.setNonStrokingColor(color);
            content.newLineAtOffset(x, baseline);
            content.showText(safe(value));
            content.endText();
        }

        private void line(float at) throws IOException {
            content.setStrokingColor(LINE);
            content.setLineWidth(0.7f);
            content.moveTo(MARGIN, at);
            content.lineTo(MARGIN + CONTENT_WIDTH, at);
            content.stroke();
        }

        private void band(float bottom, float height) throws IOException {
            content.setNonStrokingColor(BAND);
            content.addRect(MARGIN, bottom, CONTENT_WIDTH, height);
            content.fill();
        }

        private float width(String value, PDType0Font font, float size) throws IOException {
            return font.getStringWidth(safe(value)) / 1000 * size;
        }
    }

    private String date(LocalDate value) {
        return value == null ? DASH : value.format(DATE);
    }

    private String date(Instant value) {
        return value == null ? DASH : value.atZone(properties.timezone()).format(DATE);
    }

    private String dateTime(Instant value) {
        return value == null ? DASH : value.atZone(properties.timezone()).format(DATE_TIME);
    }

    private static String person(PurchaseOrderResponse.UserInfo user) {
        if (user == null) {
            return DASH;
        }
        List<String> parts = new ArrayList<>();
        for (String part : new String[]{user.name(), user.jobTitle(), user.department()}) {
            if (part != null && !part.isBlank()) {
                parts.add(part);
            }
        }
        return parts.isEmpty() ? DASH : String.join(" · ", parts);
    }

    private static String dimensions(Integer width, Integer depth, Integer height) {
        return width == null || depth == null || height == null ? DASH : width + " x " + depth + " x " + height;
    }

    private static String quantity(Long value) {
        return value == null ? DASH : number(value) + "개";
    }

    private static String quantity(Integer value) {
        return value == null ? DASH : quantity(value.longValue());
    }

    private static String money(Long value) {
        return value == null ? DASH : number(value) + "원";
    }

    private static String number(long value) {
        return String.format(Locale.KOREA, "%,d", value);
    }

    private static String value(String text) {
        return text == null || text.isBlank() ? DASH : text;
    }

    /**
     * 나눔고딕에 없는 글자가 섞이면 showText가 통째로 실패한다. 발주서를 못 내려주는 것보다 한 글자를 물음표로
     * 바꾸는 편이 나으므로, 폰트가 가진 글자만 남긴다.
     */
    private static String safe(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(text.length());
        text.codePoints().forEach(code -> {
            if (Character.isISOControl(code)) {
                result.append(' ');
            } else {
                result.appendCodePoint(code);
            }
        });
        return result.toString();
    }

    private static byte[] read(String path) throws IOException {
        try (InputStream stream = new ClassPathResource(path).getInputStream()) {
            return stream.readAllBytes();
        }
    }

    private static PDColor rgb(int red, int green, int blue) {
        return new PDColor(new float[]{red / 255f, green / 255f, blue / 255f}, PDDeviceRGB.INSTANCE);
    }
}
