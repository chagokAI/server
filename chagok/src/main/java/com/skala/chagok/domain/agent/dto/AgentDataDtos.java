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

    public record WarehouseItem(UUID warehouseId, String name, String address) {}

    public record BoxSpecItem(UUID boxSpecId, String code, String name, String description, String sizeClass,
                              Integer innerWidthMm, Integer innerDepthMm, Integer innerHeightMm, String material,
                              Integer unitPriceKrw, boolean isCustom, boolean isActive, Integer onHandQty,
                              Integer safetyQty, Integer safetyDays, Instant stockUpdatedAt, String supplier,
                              Integer leadTimeDays) {}

    /** item_event status별 건수와 마지막 발생 시각. 에이전트가 입력 기준 시각·미처리 건수를 판단한다. */
    public record EventStats(Map<String, Long> countByStatus, Instant lastOccurredAt, long pendingEventCount) {}

    public record ProductShipment(UUID productId, String code, String name, String category, Integer widthMm,
                                  Integer depthMm, Integer heightMm, String matchedBox, Double fillRatePct,
                                  long shipments) {}

    public record MonthlyShipment(String month, long shipments) {}

    public record PurchaseOrderItem(UUID purchaseOrderId, String poNo, String status, Instant createdAt,
                                    LocalDate requestedDeliveryDate, boolean fromProposal, String proposalTargetMonth,
                                    String supplier, long amountKrw) {}

    public record ProposalItem(UUID proposalId, String targetMonth, Instant generatedAt, String jobId,
                               Long totalAmount, Object newSpecs, Object forecast) {}

    /** 제안 저장 요청. id 는 에이전트가 jobId 로 만든 uuid5 라 같은 작업을 다시 저장하면 덮어쓴다. */
    public record ProposalSaveRequest(String targetMonth, Map<String, Object> summary, Instant generatedAt,
                                      Instant dataAsOf, List<ProposalLine> items) {}

    public record ProposalLine(UUID boxSpecId, Integer onHandQty, Integer safetyQty, Integer forecastUsageQty,
                               Integer proposedQty, Integer unitPriceKrw, Map<String, Object> boxSnapshot) {}

    public record ProposalSaveResponse(UUID proposalId, int itemCount) {}
}
