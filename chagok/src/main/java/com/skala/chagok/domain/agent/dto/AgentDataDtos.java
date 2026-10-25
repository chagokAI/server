package com.skala.chagok.domain.agent.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 박스 주문 제안 에이전트(box_agent business-mcp)가 /internal/v1/** 로 주고받는 형식.
 * 화면 API와 달리 계산에 필요한 원천 값을 그대로 준다. 치수는 내치수 mm, 단가는 KRW(VAT 별도).
 */
public final class AgentDataDtos {
    private AgentDataDtos() {}

    /** 박스 수용 판정 정책(chagok.matching). 에이전트가 같은 기준으로 충진율을 계산하도록 서버 값을 그대로 준다 */
    public record MatchingPolicy(boolean tiltedEnabled, int tiltedClearanceMm) {}

    public record WarehouseItem(UUID warehouseId, String name, String address) {}

    public record BoxSpecItem(UUID boxSpecId, String code, String name, String description, String sizeClass,
                              Integer innerWidthMm, Integer innerDepthMm, Integer innerHeightMm, String material,
                              Integer unitPriceKrw, boolean isCustom, boolean isActive, Integer onHandQty,
                              Integer safetyQty, Integer safetyDays, Instant stockUpdatedAt, String supplier,
                              Integer leadTimeDays) {}

    /** item_event status별 건수와 마지막 발생 시각. 에이전트가 입력 기준 시각·미처리 건수를 판단한다. */
    public record EventStats(Map<String, Long> countByStatus, Instant lastOccurredAt, long pendingEventCount) {}

    /** weightG = 상품 실중량(g). 에이전트가 운임(부피·무게 중 큰 값)을 계산할 때 쓴다 */
    public record ProductShipment(UUID productId, String code, String name, String category, Integer widthMm,
                                  Integer depthMm, Integer heightMm, Integer weightG, String matchedBox,
                                  Double fillRatePct, long shipments) {}

    /** 실제 포장 배정(item_event.box_spec_id)의 상품×박스 건수. boxCode가 null이면 들어가는 박스가 없던 건(대기 NO_AVAILABLE_BOX, 실시간 배정 전 출고) */
    public record BoxUsage(UUID productId, String boxCode, long shipments, Double fillRatePct) {}

    public record MonthlyShipment(String month, long shipments) {}

    public record PurchaseOrderItem(UUID purchaseOrderId, String poNo, String status, Instant createdAt,
                                    LocalDate requestedDeliveryDate, boolean fromProposal, String proposalTargetMonth,
                                    String supplier, long amountKrw) {}

    /**
     * 아직 입고되지 않은 발주(승인·배송 중)의 규격별 수량. 에이전트가 다음 달 시작 재고에 더한다.
     * proposalTargetMonth = 그 발주를 만든 제안서의 대상 월(제안과 무관한 발주는 null). 같은 대상 월 제안의 발주는 에이전트가 뺀다
     */
    public record InboundOrderItem(UUID boxSpecId, String code, long qty, String poNo, String status,
                                   LocalDate requestedDeliveryDate, String proposalTargetMonth) {}

    public record ProposalItem(UUID proposalId, String targetMonth, Instant generatedAt, String jobId,
                               Long totalAmount, Object newSpecs, Object forecast) {}

    /** 제안 저장 요청. id 는 에이전트가 jobId 로 만든 uuid5 라 같은 작업을 다시 저장하면 덮어쓴다. */
    public record ProposalSaveRequest(String targetMonth, Map<String, Object> summary, Instant generatedAt,
                                      Instant dataAsOf, List<ProposalLine> items) {}

    public record ProposalLine(UUID boxSpecId, Integer onHandQty, Integer safetyQty, Integer forecastUsageQty,
                               Integer proposedQty, Integer unitPriceKrw, Map<String, Object> boxSnapshot) {}

    public record ProposalSaveResponse(UUID proposalId, int itemCount) {}
}
