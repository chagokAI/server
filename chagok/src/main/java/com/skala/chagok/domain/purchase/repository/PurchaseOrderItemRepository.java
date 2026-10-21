package com.skala.chagok.domain.purchase.repository;

import com.skala.chagok.domain.purchase.entity.PurchaseOrderItem;
import com.skala.chagok.domain.purchase.entity.PurchaseOrderItemId;
import com.skala.chagok.domain.purchase.entity.PurchaseOrderStatus;

import java.util.UUID;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, PurchaseOrderItemId> {

    boolean existsByBoxSpecId(UUID boxSpecId);

    /** 규격 삭제 불가 안내용. 이 규격이 들어 있는 진행 중(statuses) 발주서 번호. */
    @Query("select distinct i.purchaseOrder.poNo from PurchaseOrderItem i "
            + "where i.boxSpec.id = :boxSpecId and i.purchaseOrder.status in :statuses")
    List<String> findPoNosByBoxSpecIdAndStatusIn(@Param("boxSpecId") UUID boxSpecId,
                                                 @Param("statuses") Collection<PurchaseOrderStatus> statuses);

    @EntityGraph(attributePaths = "boxSpec")
    List<PurchaseOrderItem> findByPurchaseOrderIdIn(Collection<UUID> purchaseOrderIds);
}
