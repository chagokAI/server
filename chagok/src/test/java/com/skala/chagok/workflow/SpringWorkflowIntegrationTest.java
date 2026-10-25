package com.skala.chagok.workflow;

import com.skala.chagok.domain.box.dto.BoxSnapshot;
import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.procurement.entity.*;
import com.skala.chagok.domain.product.service.ProductMatchService;
import com.skala.chagok.domain.purchase.service.PurchaseOrderQueryService;
import com.skala.chagok.domain.user.entity.AppUser;
import com.skala.chagok.domain.warehouse.entity.Warehouse;
import com.skala.chagok.global.auth.CurrentUserProvider;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 각 테스트의 트랜잭션은 롤백한다. 개발 데이터와 Kafka는 변경하지 않는다. */
@SpringBootTest(properties = "chagok.auth.enabled=false") @AutoConfigureMockMvc @Transactional
class SpringWorkflowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PurchaseOrderQueryService queries;
    @Autowired ProductMatchService productMatches;
    @MockitoBean CurrentUserProvider users;
    final UUID warehouse = UUID.randomUUID(), box = UUID.randomUUID(), product = UUID.randomUUID(),
            supplier = UUID.randomUUID(), user = UUID.randomUUID();
    final JsonMapper json = JsonMapper.builder().build();
    String base() { return "/api/v1/warehouses/" + warehouse; }

    @BeforeEach void fixtures() {
        jdbc.update("insert into warehouse(id,name) values (?, '와이어프레임 테스트')", warehouse);
        jdbc.update("insert into supplier(id,warehouse_id,name,lead_time_days) values (?,?,'공급사',5)", supplier, warehouse);
        jdbc.update("insert into app_user(id,warehouse_id,employee_no,name,role) values (?,?,?,'담당자','OPERATOR')", user, warehouse, user.toString().substring(0, 20));
        jdbc.update("""
                insert into box_spec(id,warehouse_id,code,name,size_class,inner_width_mm,inner_depth_mm,inner_height_mm,
                    on_hand_qty,safety_qty,target_qty,unit_price_krw,supplier_id,is_active,is_custom,alert_enabled,version)
                values (?,?,'S-1','소형','S',200,150,100,100,20,200,180,?,true,false,true,0)
                """, box, warehouse, supplier);
        jdbc.update("""
                insert into product(id,warehouse_id,code,name,width_mm,depth_mm,height_mm,matched_box_spec_id,
                    fill_rate_pct,match_status,match_source,is_active,version)
                values (?,?,'SKU-1','상품',100,100,50,?,16.67,'MATCHED','MANUAL',true,0)
                """, product, warehouse, box);
        when(users.getCurrentUser()).thenReturn(em.find(AppUser.class, user));
    }

    JsonNode postJson(String path, Map<String, ?> input, int status) throws Exception {
        var response = mvc.perform(post(base() + path).contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data");
    }
    JsonNode patchJson(String path, Map<String, ?> input, int status) throws Exception {
        var response = mvc.perform(patch(base() + path).contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data");
    }
    int version() { return jdbc.queryForObject("select version from box_spec where id=?", Integer.class, box); }
    UUID proposal() {
        var p = ProcurementProposal.builder().warehouse(em.find(Warehouse.class, warehouse))
                .targetMonth(YearMonth.now(ZoneId.of("Asia/Seoul")).toString()).summary(Map.of("method", "OBSERVATION_BASELINE")).build();
        em.persist(p);
        var b = em.find(BoxSpec.class, box);
        em.persist(ProcurementProposalItem.builder().proposal(p).boxSpec(b).boxSnapshot(BoxSnapshot.from(b))
                .proposedQty(100).forecastUsageQty(310).onHandQty(100).safetyQty(20).unitPriceKrw(180).build());
        em.flush(); return p.getId();
    }
    JsonNode draft() throws Exception {
        return postJson("/purchase-orders/from-proposal", Map.of("proposalId", proposal().toString()), 201).path("orders").get(0);
    }
    JsonNode approve(JsonNode order) throws Exception {
        return patchJson("/purchase-orders/" + order.path("id").asText(), Map.of("expectedVersion", order.path("version").asInt(), "status", "APPROVED"), 200);
    }

    @Test void dimensionChangesRecalculateRateAndReturnRealCandidatesWithWeightAndBarcode() throws Exception {
        var result = patchJson("/products/" + product, Map.of("expectedVersion", 0, "widthMm", 200, "depthMm", 150,
                "heightMm", 100, "barcode", "0012345678901", "weightG", 680, "boxSpecId", box.toString()), 200);
        assertThat(result.path("fillRatePct").decimalValue()).isEqualByComparingTo("100");
        assertThat(result.path("barcode").asText()).isEqualTo("0012345678901");
        assertThat(result.path("weightG").asInt()).isEqualTo(680);
        assertThat(result.path("matchCandidates").get(0).path("source").asText()).isEqualTo("DIMENSIONS");
        mvc.perform(get(base() + "/products/" + product + "/box-candidates")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].selected").value(true));
    }

    @Test void oversizedProductAndInactiveBoxInvalidateStaleMatching() throws Exception {
        var result = patchJson("/products/" + product, Map.of("expectedVersion", 0, "widthMm", 300), 200);
        assertThat(result.path("matchStatus").asText()).isEqualTo("UNMATCHED");
        assertThat(result.path("fillRatePct").isNull()).isTrue();
        patchJson("/box-specs/" + box, Map.of("expectedVersion", version(), "isActive", false), 200);
        mvc.perform(get(base() + "/products/" + product)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchCandidates").isEmpty());
    }

    @Test void changingBoxDimensionsRecalculatesConnectedProducts() throws Exception {
        patchJson("/box-specs/" + box, Map.of("expectedVersion", 0, "innerWidthMm", 100), 200);
        mvc.perform(get(base() + "/products/" + product)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fillRatePct").value(33.33));
        patchJson("/box-specs/" + box, Map.of("expectedVersion", version(), "innerHeightMm", 1), 200);
        mvc.perform(get(base() + "/products/" + product)).andExpect(jsonPath("$.data.matchStatus").value("UNMATCHED"));
    }

    @Test void newSpecHasExplicitStockDefaultsAndRequiresAUnitPrice() throws Exception {
        var input = new HashMap<String, Object>(Map.of("code", "M-1", "name", "중형", "material", "B", "innerWidthMm", 300,
                "innerDepthMm", 200, "innerHeightMm", 100, "isCustom", false));
        postJson("/box-specs", input, 400);
        input.put("unitPriceKrw", 250); input.put("isActive", false);
        var created = postJson("/box-specs", input, 201);
        assertThat(created.path("isActive").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select on_hand_qty from box_spec where id=?", Integer.class, UUID.fromString(created.path("id").asText()))).isZero();
    }

    JsonNode errorOf(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("error");
    }

    @Test void newSpecDerivesSizeClassFromInnerVolumeAndStoresMaterialCode() throws Exception {
        var created = postJson("/box-specs", Map.of("code", "N036호", "name", "소형", "material", "BA", "innerWidthMm", 300,
                "innerDepthMm", 200, "innerHeightMm", 100, "isCustom", false, "unitPriceKrw", 136), 201);
        assertThat(created.path("sizeClass").asText()).isEqualTo("S");
        assertThat(created.path("material").asText()).isEqualTo("BA");
        assertThat(created.path("materialLabel").asText()).isEqualTo("골판지 BA골");
        var updated = patchJson("/box-specs/" + created.path("id").asText(), Map.of("expectedVersion", 0,
                "innerWidthMm", 500, "innerDepthMm", 400, "innerHeightMm", 350), 200);
        assertThat(updated.path("sizeClass").asText()).isEqualTo("L");
    }

    @Test void unknownMaterialAndClientSizeClassAreRejectedWithTheField() throws Exception {
        var input = new HashMap<String, Object>(Map.of("code", "X-1", "name", "x", "material", "Z", "innerWidthMm", 100,
                "innerDepthMm", 100, "innerHeightMm", 100, "isCustom", false, "unitPriceKrw", 100));
        var error = errorOf(mvc.perform(post(base() + "/box-specs").contentType("application/json")
                .content(json.writeValueAsString(input))).andExpect(status().isBadRequest()));
        assertThat(error.path("fieldErrors").get(0).path("field").asText()).isEqualTo("material");
        input.put("material", "B"); input.put("sizeClass", "M");
        error = errorOf(mvc.perform(post(base() + "/box-specs").contentType("application/json")
                .content(json.writeValueAsString(input))).andExpect(status().isBadRequest()));
        assertThat(error.path("fieldErrors").get(0).path("field").asText()).isEqualTo("sizeClass");
    }

    @Test void crawledMaterialLabelIsReturnedAsCodeAndLabel() throws Exception {
        jdbc.update("update box_spec set material='골판지 B골' where id=?", box);
        mvc.perform(get(base() + "/box-specs/" + box)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.material").value("B"))
                .andExpect(jsonPath("$.data.materialLabel").value("골판지 B골"));
    }

    @Test void blockedDeletionExplainsLinkedProductsAndOpenOrders() throws Exception {
        draft();
        var error = errorOf(mvc.perform(delete(base() + "/box-specs/" + box).param("expectedVersion", String.valueOf(version())))
                .andExpect(status().isConflict()));
        assertThat(error.path("code").asText()).isEqualTo("REFERENCED_SPEC");
        assertThat(error.path("details").path("active").asBoolean()).isTrue();
        assertThat(error.path("details").path("linkedProductCount").asLong()).isEqualTo(1);
        assertThat(error.path("details").path("openPurchaseOrderNos")).hasSize(1);
        assertThat(error.path("details").path("onHandQty").asInt()).isEqualTo(100);
    }

    @Test void suppliersAndMaterialsFeedTheSpecModalDropdowns() throws Exception {
        jdbc.update("insert into supplier(id,warehouse_id,name,lead_time_days) values (?,?,'가나포장',3)", UUID.randomUUID(), warehouse);
        mvc.perform(get(base() + "/suppliers")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("가나포장"));
        mvc.perform(get(base() + "/suppliers").param("keyword", "가나")).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/v1/box-materials")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[0].code").value("A"))
                .andExpect(jsonPath("$.data[0].label").value("골판지 A골"));
    }

    UUID insertBox(String code, int w, int d, int h) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into box_spec(id,warehouse_id,code,name,size_class,inner_width_mm,inner_depth_mm,inner_height_mm,
                    on_hand_qty,safety_qty,unit_price_krw,supplier_id,is_active,is_custom,alert_enabled,version)
                values (?,?,?,?,'S',?,?,?,0,0,100,?,true,false,true,0)
                """, id, warehouse, code, code, w, d, h, supplier);
        return id;
    }
    UUID insertUnmatchedProduct(String code, Integer w, Integer d, Integer h) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into product(id,warehouse_id,code,name,width_mm,depth_mm,height_mm,match_status,is_active,version)
                values (?,?,?,?,?,?,?,'UNMATCHED',true,0)
                """, id, warehouse, code, code, w, d, h);
        return id;
    }

    @Test void dimensionChangeAppliesTheTopCandidateToNonManualProducts() throws Exception {
        UUID tight = insertBox("S-0", 110, 110, 60);
        UUID item = insertUnmatchedProduct("SKU-2", null, null, null);
        var result = patchJson("/products/" + item, Map.of("expectedVersion", 0, "widthMm", 100, "depthMm", 100, "heightMm", 30), 200);
        assertThat(result.path("matchedBoxSpec").path("id").asText()).isEqualTo(tight.toString());
        assertThat(result.path("matchSource").asText()).isEqualTo("DIMENSIONS");
        assertThat(result.path("fillRatePct").decimalValue()).isEqualByComparingTo("41.32");
        assertThat(result.path("matchStatus").asText()).isEqualTo("REVIEW");
        assertThat(result.path("matchCandidates").get(0).path("boxSpec").path("id").asText()).isEqualTo(tight.toString());
        result = patchJson("/products/" + item, Map.of("expectedVersion", result.path("version").asInt(),
                "widthMm", 105, "depthMm", 105, "heightMm", 58), 200);
        assertThat(result.path("matchStatus").asText()).isEqualTo("MATCHED");
    }

    @Test void dimensionChangeKeepsAManuallyChosenBox() throws Exception {
        insertBox("S-0", 110, 110, 60);
        var result = patchJson("/products/" + product, Map.of("expectedVersion", 0, "heightMm", 50), 200);
        assertThat(result.path("matchedBoxSpec").path("id").asText()).isEqualTo(box.toString());
        assertThat(result.path("matchSource").asText()).isEqualTo("MANUAL");
    }

    @Test void bulkRecalculationFillsOnlyUnmatchedProducts() throws Exception {
        insertUnmatchedProduct("SKU-FIT", 195, 145, 95);
        insertUnmatchedProduct("SKU-NODIM", null, null, null);
        insertUnmatchedProduct("SKU-HUGE", 1000, 1000, 1000);
        var result = postJson("/product-matches/recalculate", Map.of(), 200);
        assertThat(result.path("targetCount").asInt()).isEqualTo(3);
        assertThat(result.path("matchedCount").asInt()).isEqualTo(1);
        assertThat(result.path("unmatchedCount").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select match_source from product where code='SKU-FIT'", String.class)).isEqualTo("DIMENSIONS");
        assertThat(jdbc.queryForObject("select match_source from product where id=?", String.class, product)).isEqualTo("MANUAL");
        assertThat(postJson("/product-matches/recalculate", Map.of(), 200).path("targetCount").asInt()).isEqualTo(2);
    }

    UUID insertMatchedProduct(String code, String fill, String status, String source, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into product(id,warehouse_id,code,name,width_mm,depth_mm,height_mm,matched_box_spec_id,
                    fill_rate_pct,match_status,match_source,is_active,version)
                values (?,?,?,?,100,100,50,?,cast(? as numeric),?,?,?,0)
                """, id, warehouse, code, code, box, fill, status, source, active);
        return id;
    }
    String statusOf(UUID id) { return jdbc.queryForObject("select match_status from product where id=?", String.class, id); }
    int productVersion(UUID id) { return jdbc.queryForObject("select version from product where id=?", Integer.class, id); }

    @Test void statusResyncAppliesTheCurrentThresholdWithoutChangingBoxesOrUntouchedRows() {
        UUID raised = insertMatchedProduct("SKU-60", "60.00", "REVIEW", "DIMENSIONS", true);
        UUID lowered = insertMatchedProduct("SKU-40", "40.00", "MATCHED", "DIMENSIONS", true);
        UUID low = insertMatchedProduct("SKU-30", "30.00", "REVIEW", "DIMENSIONS", true);
        UUID tilted = insertMatchedProduct("SKU-TILT", "90.00", "REVIEW", "TILTED", true);
        UUID noRate = insertMatchedProduct("SKU-NORATE", null, "REVIEW", "DIMENSIONS", true);
        UUID inactive = insertMatchedProduct("SKU-OFF", "60.00", "REVIEW", "DIMENSIONS", false);
        UUID unmatched = insertUnmatchedProduct("SKU-NONE", 10, 10, 10);

        var result = productMatches.resyncStatuses(warehouse);

        // 기본 fixture의 직접 지정 상품(16.67%)과 위의 60·40·30·TILTED 네 개를 다시 판정한다.
        assertThat(result).isEqualTo(new ProductMatchService.StatusResync(5, 1, 1, 1));
        assertThat(statusOf(raised)).isEqualTo("MATCHED");
        assertThat(statusOf(lowered)).isEqualTo("REVIEW");
        assertThat(statusOf(low)).isEqualTo("REVIEW");
        assertThat(statusOf(tilted)).isEqualTo("REVIEW");
        assertThat(statusOf(product)).isEqualTo("MATCHED");
        assertThat(statusOf(noRate)).isEqualTo("REVIEW");
        assertThat(statusOf(inactive)).isEqualTo("REVIEW");
        assertThat(statusOf(unmatched)).isEqualTo("UNMATCHED");
        assertThat(productVersion(raised)).isEqualTo(1);
        assertThat(productVersion(lowered)).isEqualTo(1);
        assertThat(List.of(productVersion(low), productVersion(tilted), productVersion(product), productVersion(noRate),
                productVersion(inactive), productVersion(unmatched))).containsOnly(0);
        assertThat(jdbc.queryForMap("select matched_box_spec_id, fill_rate_pct from product where id=?", raised))
                .containsEntry("matched_box_spec_id", box).containsEntry("fill_rate_pct", new java.math.BigDecimal("60.00"));

        assertThat(productMatches.resyncStatuses(warehouse)).isEqualTo(new ProductMatchService.StatusResync(5, 0, 0, 1));
    }

    @Test void safetyDaysArePersistedAndCalculatedFromAvailableReferenceWithoutClaimingActualConsumption() throws Exception {
        proposal();
        var result = patchJson("/box-inventories/" + box + "/policy", Map.of("expectedVersion", 0, "safetyDays", 7, "alertEnabled", false), 200);
        int expected = (int) Math.ceil(310.0 * 7 / YearMonth.now(ZoneId.of("Asia/Seoul")).lengthOfMonth());
        assertThat(result.path("safetyQty").asInt()).isEqualTo(expected);
        assertThat(result.path("usageSource").asText()).isEqualTo("REFERENCE_FORECAST");
        assertThat(result.path("alertEnabled").asBoolean()).isFalse();
        mvc.perform(get(base() + "/box-stocks")).andExpect(jsonPath("$.data.items[0].stock.safetyDays").value(7));
    }

    @Test void absentUsageStoresAutomaticPolicyAndAllowsExplicitQuantityAndSavesAnAlert() throws Exception {
        var automatic = patchJson("/box-inventories/" + box + "/policy", Map.of("expectedVersion", 0, "safetyDays", 7), 200);
        assertThat(automatic.path("safetyDays").asInt()).isEqualTo(7);
        assertThat(automatic.path("safetyQty").isNull()).isTrue();
        assertThat(automatic.path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("select safety_qty from box_spec where id=?", Integer.class, box)).isNull();
        assertThat(jdbc.queryForObject("select safety_days from box_spec where id=?", Integer.class, box)).isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from notification where recipient_id=? and category='INVENTORY'", Integer.class, user)).isZero();
        patchJson("/box-inventories/" + box + "/policy", Map.of("expectedVersion", automatic.path("version").asInt(), "safetyDays", 7, "safetyQty", 200), 200);
        assertThat(jdbc.queryForObject("select count(*) from notification where recipient_id=? and category='INVENTORY'", Integer.class, user)).isEqualTo(1);
    }

    @Test void receiptIsIdempotentAndVersionConflictsCannotChangeStock() throws Exception {
        var input = Map.of("requestId", UUID.randomUUID().toString(), "boxSpecId", box.toString(), "expectedVersion", 0,
                "qty", 50, "receivedDate", LocalDate.now(ZoneId.of("Asia/Seoul")).toString());
        var first = postJson("/inventory-receipts", input, 201);
        assertThat(postJson("/inventory-receipts", input, 201).path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(jdbc.queryForObject("select on_hand_qty from box_spec where id=?", Integer.class, box)).isEqualTo(150);
        var changed = new HashMap<String, Object>(input); changed.put("qty", 51);
        postJson("/inventory-receipts", changed, 409);
        changed.put("requestId", UUID.randomUUID().toString()); postJson("/inventory-receipts", changed, 409);
    }

    @Test void countedAdjustmentKeepsAnAuditAndUpdatesStockSummary() throws Exception {
        var result = postJson("/inventory-adjustments", Map.of("requestId", UUID.randomUUID().toString(), "boxSpecId", box.toString(),
                "expectedVersion", 0, "onHandQty", 5, "reason", "실사 확인"), 201);
        assertThat(result.path("deltaQty").asInt()).isEqualTo(-95);
        mvc.perform(get(base() + "/box-stocks")).andExpect(jsonPath("$.data.summary.stock.totalOnHandQty").value(5))
                .andExpect(jsonPath("$.data.items[0].stock.status").value("SHORTAGE"));
    }

    @Test void draftsAreIdempotentAndApprovalRejectsOldVersionAndInvalidStateTransitions() throws Exception {
        UUID proposal = proposal();
        var first = postJson("/purchase-orders/from-proposal", Map.of("proposalId", proposal.toString()), 201).path("orders").get(0);
        var again = postJson("/purchase-orders/from-proposal", Map.of("proposalId", proposal.toString()), 200).path("orders").get(0);
        assertThat(again.path("id").asText()).isEqualTo(first.path("id").asText());
        var edited = patchJson("/purchase-orders/" + first.path("id").asText(), Map.of("expectedVersion", 0,
                "items", List.of(Map.of("boxSpecId", box.toString(), "qty", 200))), 200);
        assertThat(edited.path("version").asInt()).isGreaterThan(first.path("version").asInt());
        patchJson("/purchase-orders/" + first.path("id").asText(), Map.of("expectedVersion", 0, "status", "APPROVED"), 409);
        var approved = approve(edited);
        patchJson("/purchase-orders/" + approved.path("id").asText(), Map.of("expectedVersion", approved.path("version").asInt(), "status", "RECEIVED"), 409);
        assertThat(approved.path("totalAmountKrw").asLong()).isEqualTo(36000);
    }

    @Test void partialReceiptsRejectOverReceiptAndCompleteOrderOnlyWhenAllBoxesArrive() throws Exception {
        var order = approve(draft());
        var input = new HashMap<String, Object>(Map.of("requestId", UUID.randomUUID().toString(), "boxSpecId", box.toString(),
                "expectedVersion", version(), "purchaseOrderId", order.path("id").asText(), "qty", 40,
                "receivedDate", LocalDate.now(ZoneId.of("Asia/Seoul")).toString()));
        postJson("/inventory-receipts", input, 201);
        mvc.perform(get(base() + "/purchase-orders/open")).andExpect(jsonPath("$.data[0].items[0].remainingQty").value(60));
        input.put("requestId", UUID.randomUUID().toString()); input.put("expectedVersion", version()); input.put("qty", 61);
        postJson("/inventory-receipts", input, 400);
        input.put("qty", 60); postJson("/inventory-receipts", input, 201);
        mvc.perform(get(base() + "/purchase-orders/" + order.path("id").asText())).andExpect(jsonPath("$.data.status").value("RECEIVED"));
        mvc.perform(get(base() + "/purchase-orders/open")).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test void inProgressSummaryCountsApprovedAndShippingAndAmountSortUsesWholeResultSet() throws Exception {
        approve(draft());
        var second = draft();
        var edited = patchJson("/purchase-orders/" + second.path("id").asText(), Map.of("expectedVersion", 0,
                "items", List.of(Map.of("boxSpecId", box.toString(), "qty", 200))), 200);
        approve(edited);
        var result = queries.list(warehouse, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), null, null, 0, 1, "totalAmountKrw,desc");
        assertThat(result.summary().inProgressCount()).isEqualTo(2);
        assertThat(result.summary().pendingCount()).isZero();
        assertThat(result.items().getFirst().totalAmountKrw()).isEqualTo(36000);
        assertThat(queries.list(warehouse, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), null, null,
                0, 1, "totalAmountKrw,asc").items().getFirst().totalAmountKrw()).isEqualTo(18000);
    }

    @Test void malformedNumbersAndUnrelatedTenantIdsDoNotChangeData() throws Exception {
        mvc.perform(patch(base() + "/products/" + product).contentType("application/json")
                .content("{\"expectedVersion\":0,\"widthMm\":12.5}")).andExpect(status().isBadRequest());
        patchJson("/products/" + UUID.randomUUID(), Map.of("expectedVersion", 0, "weightG", 1), 404);
        patchJson("/products/" + product, Map.of("expectedVersion", -1, "weightG", 1), 400);
    }

    @Test void unavailableStockStaysUnknownUntilAnExplicitCountedAdjustment() throws Exception {
        jdbc.update("update box_spec set on_hand_qty=null where id=?", box);
        mvc.perform(get(base() + "/box-stocks")).andExpect(jsonPath("$.data.items[0].stock.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.summary.stock.totalOnHandQty").isEmpty())
                .andExpect(jsonPath("$.data.summary.stock.unknownSpecCount").value(1));
        var movement = postJson("/inventory-adjustments", Map.of("requestId", UUID.randomUUID().toString(), "boxSpecId", box.toString(),
                "expectedVersion", 0, "onHandQty", 15, "reason", "최초 실사"), 201);
        assertThat(movement.path("beforeQty").isNull()).isTrue();
        assertThat(movement.path("deltaQty").isNull()).isTrue();
        mvc.perform(get(base() + "/box-inventories/" + box + "/movements")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].afterQty").value(15));
    }

    @Test void supplierGroupsBecomeSeparateDraftsAndRepeatedRequestsReuseThem() throws Exception {
        UUID secondSupplier = UUID.randomUUID(), secondBox = UUID.randomUUID();
        jdbc.update("insert into supplier(id,warehouse_id,name,lead_time_days) values (?,?,'다른 공급사',3)", secondSupplier, warehouse);
        jdbc.update("insert into box_spec(id,warehouse_id,code,name,is_active,unit_price_krw,supplier_id,version) values (?,?,'M-1','중형',true,250,?,0)",
                secondBox, warehouse, secondSupplier);
        UUID proposalId = proposal();
        em.persist(ProcurementProposalItem.builder().proposal(em.find(ProcurementProposal.class, proposalId)).boxSpec(em.find(BoxSpec.class, secondBox))
                .proposedQty(50).forecastUsageQty(30).unitPriceKrw(250).build()); em.flush();
        var created = postJson("/purchase-orders/from-proposal", Map.of("proposalId", proposalId.toString()), 201).path("orders");
        assertThat(created.size()).isEqualTo(2);
        assertThat(created.get(0).path("supplier").path("id").asText()).isNotEqualTo(created.get(1).path("supplier").path("id").asText());
        var again = postJson("/purchase-orders/from-proposal", Map.of("proposalId", proposalId.toString()), 200).path("orders");
        assertThat(again.size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from purchase_order where proposal_id=?", Integer.class, proposalId)).isEqualTo(2);
    }

    @Test void futureReceiptAndUnrelatedSpecAreRejectedWithoutChangingStock() throws Exception {
        var input = new HashMap<String, Object>(Map.of("requestId", UUID.randomUUID().toString(), "boxSpecId", box.toString(),
                "expectedVersion", 0, "qty", 10, "receivedDate", LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString()));
        postJson("/inventory-receipts", input, 400);
        input.put("receivedDate", LocalDate.now(ZoneId.of("Asia/Seoul")).toString()); input.put("boxSpecId", UUID.randomUUID().toString());
        postJson("/inventory-receipts", input, 404);
        assertThat(jdbc.queryForObject("select on_hand_qty from box_spec where id=?", Integer.class, box)).isEqualTo(100);
    }

    @Test void barcodeAcceptsAlphanumericIdentifiersAndRejectsOtherCharacters() throws Exception {
        patchJson("/products/" + product, Map.of("expectedVersion", 0, "barcode", "NCW2S06390"), 200);
        assertThat(jdbc.queryForObject("select barcode from product where id=?", String.class, product)).isEqualTo("NCW2S06390");
        patchJson("/products/" + product, Map.of("expectedVersion", 1, "barcode", "NCW-2S06390"), 400);
        patchJson("/products/" + product, Map.of("expectedVersion", 1, "barcode", "1".repeat(31)), 400);
    }

    @Test void barcodeUniquenessIsCheckedAndZeroQuantityCannotCreateAnOrder() throws Exception {
        patchJson("/products/" + product, Map.of("expectedVersion", 0, "barcode", "000123"), 200);
        UUID another = UUID.randomUUID();
        jdbc.update("insert into product(id,warehouse_id,code,is_active,version) values (?,?,'SKU-2',true,0)", another, warehouse);
        patchJson("/products/" + another, Map.of("expectedVersion", 0, "barcode", "000123"), 400);
        UUID proposalId = proposal();
        jdbc.update("update procurement_proposal_item set proposed_qty=0 where proposal_id=?", proposalId); em.clear();
        postJson("/purchase-orders/from-proposal", Map.of("proposalId", proposalId.toString()), 409);
    }
}
