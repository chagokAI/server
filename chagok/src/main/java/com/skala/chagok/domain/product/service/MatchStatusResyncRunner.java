package com.skala.chagok.domain.product.service;

import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 매칭 상태 기준이 바뀐 뒤, 이미 저장된 상품 상태를 새 기준으로 한 번 다시 맞춘다.
 *
 * <p>상태는 상품을 다시 매칭할 때만 계산되므로 기준만 바꾸면 기존 상품에 옛 기준이 남는다. 상품 행을 바꾸는 작업이라
 * chagok.matching.resync-statuses-on-startup=true일 때만 기동 시 한 번 돈다. 센터마다 따로 확정하며,
 * 한 센터에서 실패하면 기동을 멈춰 일부만 바뀐 상태를 조용히 넘기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "chagok.matching.resync-statuses-on-startup", havingValue = "true")
class MatchStatusResyncRunner implements ApplicationRunner {
    private static final int PAGE_SIZE = 100;

    private final WarehouseRepository warehouses;
    private final ProductMatchService matches;

    @Override
    public void run(ApplicationArguments args) {
        for (int page = 0; ; page++) {
            var ids = warehouses.findIds(PageRequest.of(page, PAGE_SIZE));
            for (var id : ids) {
                var result = matches.resyncStatuses(id);
                log.info("매칭 상태 재판정: warehouseId={}, checked={}, toMatched={}, toReview={}, skipped={}",
                        id, result.checked(), result.toMatched(), result.toReview(), result.skipped());
            }
            if (ids.size() < PAGE_SIZE) break;
        }
    }
}
