package com.skala.chagok.domain.procurement.entity;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.dto.BoxSnapshot;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "procurement_proposal_item")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProcurementProposalItem {

    @Builder.Default
    @EmbeddedId
    private ProcurementProposalItemId id = new ProcurementProposalItemId();

    @MapsId("proposalId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposal_id", nullable = false)
    private ProcurementProposal proposal;

    @MapsId("boxSpecId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "box_spec_id", nullable = false)
    private BoxSpec boxSpec;

    @Column(name = "on_hand_qty")
    private Integer onHandQty;

    @Column(name = "safety_qty")
    private Integer safetyQty;

    @Column(name = "forecast_usage_qty")
    private Integer forecastUsageQty;

    @Column(name = "proposed_qty")
    private Integer proposedQty;

    @Column(name = "unit_price_krw")
    private Integer unitPriceKrw;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "box_snapshot", columnDefinition = "jsonb")
    private BoxSnapshot boxSnapshot;
}
