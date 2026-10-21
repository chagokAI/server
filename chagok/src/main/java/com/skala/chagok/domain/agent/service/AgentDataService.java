package com.skala.chagok.domain.agent.service;

import com.skala.chagok.domain.agent.dto.AgentDataDtos.*;
import com.skala.chagok.domain.agent.repository.AgentDataRepository;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.BusinessException;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 에이전트 조회 기간 해석(센터 시간대)과 제안 저장 검증을 맡는다. */
@Service @RequiredArgsConstructor
public class AgentDataService {
    private final AgentDataRepository repository;
    private final ChagokProperties properties;

    public List<WarehouseItem> warehouses() { return repository.warehouses(); }

    public List<BoxSpecItem> boxSpecs(UUID warehouseId, boolean activeOnly) {
        requireWarehouse(warehouseId);
        return repository.boxSpecs(warehouseId, activeOnly);
    }

    public EventStats eventStats(UUID warehouseId) {
        requireWarehouse(warehouseId);
        return repository.eventStats(warehouseId);
    }

    /** from~to 는 센터 시간대 날짜(양 끝 포함). */
    public List<ProductShipment> productShipments(UUID warehouseId, LocalDate from, LocalDate to, List<String> statuses) {
        requireWarehouse(warehouseId);
        requireRange(from, to);
        ZoneId zone = properties.timezone();
        return repository.productShipments(warehouseId, from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant(), statuses);
    }

    /** fromMonth~toMonth(YYYY-MM, 양 끝 포함) 월별 건수. 건수가 없는 달은 빠진다. */
    public List<MonthlyShipment> monthlyShipments(UUID warehouseId, YearMonth fromMonth, YearMonth toMonth,
                                                  List<String> statuses) {
        requireWarehouse(warehouseId);
        if (fromMonth.isAfter(toMonth)) throw BusinessException.invalidField("fromMonth", "toMonth 이전이어야 합니다.");
        ZoneId zone = properties.timezone();
        return repository.monthlyShipments(warehouseId, fromMonth.atDay(1).atStartOfDay(zone).toInstant(),
                toMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant(), statuses, zone);
    }

    public List<PurchaseOrderItem> purchaseOrders(UUID warehouseId, LocalDate from, LocalDate to) {
        requireWarehouse(warehouseId);
        requireRange(from, to);
        return repository.purchaseOrders(warehouseId, from, to, properties.timezone());
    }

    public List<ProposalItem> proposals(UUID warehouseId, int limit) {
        requireWarehouse(warehouseId);
        if (limit < 1 || limit > 100) throw BusinessException.invalidField("limit", "1~100 사이여야 합니다.");
        return repository.proposals(warehouseId, limit);
    }

    @Transactional
    public ProposalSaveResponse saveProposal(UUID warehouseId, UUID proposalId, ProposalSaveRequest req) {
        requireWarehouse(warehouseId);
        if (req.targetMonth() == null || !req.targetMonth().matches("\\d{4}-\\d{2}"))
            throw BusinessException.invalidField("targetMonth", "YYYY-MM 형식이어야 합니다.");
        if (req.summary() == null) throw BusinessException.invalidField("summary", "필수입니다.");
        List<ProposalLine> items = req.items() == null ? List.of() : req.items();
        UUID owner = repository.proposalWarehouse(proposalId);
        if (owner != null && !owner.equals(warehouseId))
            throw BusinessException.invalidField("proposalId", "다른 센터의 제안입니다.");
        Set<UUID> ids = new HashSet<>();
        for (ProposalLine line : items) {
            if (line.boxSpecId() == null || !ids.add(line.boxSpecId()))
                throw BusinessException.invalidField("items.boxSpecId", "비어 있거나 중복된 규격이 있습니다.");
            if (line.proposedQty() == null || line.proposedQty() < 0)
                throw BusinessException.invalidField("items.proposedQty", "0 이상이어야 합니다.");
        }
        if (repository.countBoxSpecs(warehouseId, ids) != ids.size())
            throw BusinessException.invalidField("items.boxSpecId", "센터에 없는 박스 규격이 있습니다.");
        repository.upsertProposal(proposalId, warehouseId, new ProposalSaveRequest(req.targetMonth(), req.summary(),
                req.generatedAt(), req.dataAsOf(), items));
        return new ProposalSaveResponse(proposalId, items.size());
    }

    private void requireWarehouse(UUID warehouseId) {
        if (!repository.warehouseExists(warehouseId)) throw BusinessException.notFound("센터");
    }

    private static void requireRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) throw BusinessException.invalidField("from", "to 이전이어야 합니다.");
    }
}
