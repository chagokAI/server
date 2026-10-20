package com.skala.chagok.domain.purchase.dto.response;
import java.util.*;
public record DraftGroupResponse(UUID proposalId, List<PurchaseOrderResponse> orders) {}
