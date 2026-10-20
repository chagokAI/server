package com.skala.chagok.dlanalysis;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.skala.chagok.domain.product.entity.Product;

import lombok.extern.slf4j.Slf4j;

/**
 * DL 분석 결과를 item_event에 반영한다.
 *
 * <p>1단계 규칙: DL이 식별한 바코드로 같은 센터의 활성 상품을 찾고, 그 상품에 이미 저장된 매칭 박스·충전율을
 * item_event에 옮겨 적는다. placement는 채우지 않는다 — 배치 좌표가 없을 때 치수로 임의 위치를 만들지 않는다는
 * 기능 명세(DASH-02) 때문이다. 상품과 박스 재고는 바꾸지 않는다. 분석만으로 재고를 차감하지 않는다는 명세를 따른다.
 *
 * <p>모든 갱신은 status가 PENDING일 때만 일어난다. 갱신 건수가 0이면 이미 처리된 작업의 재전달로 보고 넘어간다.
 */
@Slf4j
@Service
public class DlAnalysisResultRecorder {

    private final DlAnalysisItemEventRepository itemEvents;
    private final DlAnalysisProductRepository products;

    DlAnalysisResultRecorder(DlAnalysisItemEventRepository itemEvents, DlAnalysisProductRepository products) {
        this.itemEvents = itemEvents;
        this.products = products;
    }

    @Transactional
    public void recordIdentified(String eventId, UUID warehouseId, String barcode) {
        List<Product> found = products.findByWarehouse_IdAndCodeAndIsActiveTrue(warehouseId, barcode);
        if (found.isEmpty()) {
            fail(eventId, DlAnalysisFailureReason.PRODUCT_NOT_FOUND, "barcode=" + barcode);
            return;
        }
        if (found.size() > 1) {
            fail(eventId, DlAnalysisFailureReason.PRODUCT_AMBIGUOUS, "barcode=" + barcode + ", count=" + found.size());
            return;
        }
        Product product = found.getFirst();
        int updated = itemEvents.markSucceeded(eventId, product, product.getMatchedBoxSpec(), product.getFillRatePct());
        if (updated == 0) {
            log.info("DL 분석 결과 생략(이미 처리됨): eventId={}", eventId);
            return;
        }
        if (product.getMatchedBoxSpec() == null) {
            log.info("DL 분석 성공, 상품에 매칭 박스 없음: eventId={}, reason=NO_MATCHED_BOX, productId={}",
                    eventId, product.getId());
        }
    }

    @Transactional
    public void recordFailure(String eventId, DlAnalysisFailureReason reason, String detail) {
        fail(eventId, reason, detail);
    }

    private void fail(String eventId, DlAnalysisFailureReason reason, String detail) {
        int updated = itemEvents.markFailed(eventId);
        if (updated == 0) {
            log.info("DL 분석 실패 기록 생략(이미 처리됨): eventId={}, reason={}", eventId, reason);
            return;
        }
        log.warn("DL 분석 실패: eventId={}, reason={}, detail={}", eventId, reason, detail);
    }
}
