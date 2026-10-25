package com.skala.chagok.domain.purchase.controller;

import com.skala.chagok.domain.purchase.dto.response.*;
import com.skala.chagok.domain.purchase.entity.PurchaseOrderStatus;
import com.skala.chagok.domain.purchase.service.PurchaseOrderPdfRenderer;
import com.skala.chagok.domain.purchase.service.PurchaseOrderQueryService;
import com.skala.chagok.global.response.*;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/warehouses/{warehouseId}/purchase-orders")
public class PurchaseOrderQueryController {
    private final PurchaseOrderQueryService service;
    private final PurchaseOrderPdfRenderer pdfRenderer;
    private final ApiResponses responses;

    @GetMapping
    public ApiResponse<PurchaseOrderPageResponse> list(@PathVariable UUID warehouseId,
            @RequestParam LocalDate from, @RequestParam LocalDate to, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) PurchaseOrderStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "false") boolean historyOnly) {
        return responses.of(service.list(warehouseId, from, to, keyword, status, page, size, sort, historyOnly));
    }

    @GetMapping("/{purchaseOrderId}")
    public ApiResponse<PurchaseOrderResponse> get(@PathVariable UUID warehouseId, @PathVariable UUID purchaseOrderId) {
        return responses.of(service.get(warehouseId, purchaseOrderId));
    }

    /** PO-PDF: 발주서 상세의 PDF 다운로드. 문서이므로 {data,meta} Envelope 없이 파일 그대로 내려준다. */
    @GetMapping(value = "/{purchaseOrderId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID warehouseId, @PathVariable UUID purchaseOrderId) {
        PurchaseOrderResponse order = service.get(warehouseId, purchaseOrderId);
        byte[] body = pdfRenderer.render(order);
        // 한글 파일명은 filename*(RFC 5987)로 내보낸다.
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("발주서_" + order.poNo() + ".pdf", StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
