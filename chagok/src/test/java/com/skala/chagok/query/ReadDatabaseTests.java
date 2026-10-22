package com.skala.chagok.query;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.box.service.BoxSpecService;
import com.skala.chagok.domain.category.entity.Category;
import com.skala.chagok.domain.dashboard.dto.MetricSeries;
import com.skala.chagok.domain.dashboard.repository.MetricQueryRepository;
import com.skala.chagok.domain.event.entity.*;
import com.skala.chagok.domain.event.repository.ItemEventRepository;
import com.skala.chagok.domain.job.entity.AnalysisJob;
import com.skala.chagok.domain.job.repository.AnalysisJobRepository;
import com.skala.chagok.domain.notification.entity.Notification;
import com.skala.chagok.domain.notification.repository.NotificationRepository;
import com.skala.chagok.domain.notification.service.NotificationQueryService;
import com.skala.chagok.domain.notification.dto.NotificationPageResponse;
import com.skala.chagok.domain.procurement.entity.*;
import com.skala.chagok.domain.procurement.dto.CostForecastResult;
import com.skala.chagok.domain.procurement.repository.*;
import com.skala.chagok.domain.product.entity.Product;
import com.skala.chagok.domain.product.repository.ProductRepository;
import com.skala.chagok.domain.purchase.entity.*;
import com.skala.chagok.domain.purchase.repository.*;
import com.skala.chagok.domain.purchase.service.PurchaseOrderQueryService;
import com.skala.chagok.domain.supplier.entity.Supplier;
import com.skala.chagok.domain.supplier.repository.SupplierRepository;
import com.skala.chagok.domain.user.entity.AppUser;
import com.skala.chagok.domain.warehouse.entity.Warehouse;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import com.skala.chagok.global.auth.CurrentUserProvider;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.web.WarehouseQueryGuard;
import jakarta.persistence.EntityManager;
import java.io.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.h2.tools.RunScript;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.*;
import org.hibernate.dialect.PostgreSQLDialect;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** PostgreSQL 형식의 스키마·조회 쿼리를 H2 호환 모드에서 검증한다. pgvector 연산 자체는 대상이 아니다. */
class ReadDatabaseTests {
    private static StandardServiceRegistry registry;
    private static SessionFactory factory;
    private static JdbcTemplate jdbc;
    private static java.sql.Connection schemaConnection;
    private EntityManager em;
    private JpaRepositoryFactory repositories;
    private final UUID warehouseId = UUID.randomUUID();
    private final ChagokProperties properties = new ChagokProperties(ZoneId.of("Asia/Seoul"), 300, new ChagokProperties.Inventory(3, 14), new ChagokProperties.BoxSize(10, 50));

    @BeforeAll static void database() throws Exception {
        String url = "jdbc:h2:mem:read-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        StringWriter schema = new StringWriter();
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", PostgreSQLDialect.class.getName())
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("jakarta.persistence.jdbc.driver", "org.h2.Driver")
                .applySetting("jakarta.persistence.jdbc.url", url)
                .applySetting("jakarta.persistence.jdbc.user", "sa")
                .applySetting("jakarta.persistence.jdbc.password", "")
                .applySetting("jakarta.persistence.schema-generation.database.action", "none")
                .applySetting("jakarta.persistence.schema-generation.scripts.action", "create")
                .applySetting("jakarta.persistence.schema-generation.scripts.create-target", schema).build();
        MetadataSources metadata = new MetadataSources(registry);
        for (Class<?> type : List.of(Warehouse.class, AppUser.class, Supplier.class, Category.class, BoxSpec.class,
                Product.class, ItemEvent.class, Notification.class, ProcurementProposal.class, ProcurementProposalItem.class,
                PurchaseOrder.class, PurchaseOrderItem.class, AnalysisJob.class, CostForecast.class)) metadata.addAnnotatedClass(type);
        // H2 JSON은 PostgreSQL의 PGobject 대신 byte[]를 반환하므로 테스트에서는 문자열 JSON 추출기를 쓴다.
        factory = metadata.getMetadataBuilder().applyTypes((contributions, services) ->
                contributions.contributeJdbcType(org.hibernate.type.descriptor.jdbc.JsonJdbcType.INSTANCE))
                .build().buildSessionFactory();
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        schemaConnection = dataSource.getConnection();
        RunScript.execute(schemaConnection, new StringReader(schema.toString().replace("vector(384)", "real array")
                .replace("timestamptz", "timestamp with time zone")));
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach void prepare() {
        em = factory.createEntityManager();
        repositories = new JpaRepositoryFactory(em);
        jdbc.update("insert into warehouse (id, name) values (?, ?)", warehouseId, "조회 테스트 센터");
    }

    @AfterEach void closeEntityManager() { if (em != null) em.close(); }
    @AfterAll static void closeDatabase() throws Exception {
        if (factory != null) factory.close();
        if (schemaConnection != null) schemaConnection.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    @Test void allNewAndChangedRepositoryQueriesParseAgainstActualMappings() {
        for (Class<?> type : List.of(BoxSpecRepository.class, ProductRepository.class, ItemEventRepository.class,
                ProcurementProposalRepository.class, ProcurementProposalItemRepository.class, CostForecastRepository.class,
                AnalysisJobRepository.class, PurchaseOrderRepository.class, PurchaseOrderItemRepository.class, NotificationRepository.class))
            assertThat(repositories.getRepository(type)).isNotNull();
    }

    @Test void sceneOrdersTiesByEventIdAndIsolatesWarehouses() {
        UUID otherWarehouse = UUID.randomUUID();
        jdbc.update("insert into warehouse (id) values (?)", otherWarehouse);
        event(warehouseId, "b", "2026-10-19T00:00:00Z", "PENDING", null);
        event(warehouseId, "a", "2026-10-19T00:00:00Z", "SUCCEEDED", "80.00");
        event(otherWarehouse, "other", "2026-10-20T00:00:00Z", "PENDING", null);
        var repository = repositories.getRepository(ItemEventRepository.class);
        var rows = repository.findRecent(warehouseId, PageRequest.of(0, 100));
        assertThat(rows).extracting(value -> value.getId().getEventId()).containsExactly(warehouseId + "a", warehouseId + "b");
        assertThat(repository.countByWarehouseIdAndStatus(warehouseId, "PENDING")).isEqualTo(1);
    }

    @Test void metricsAggregateOnlyRequestedWarehouseAndUseKoreanDayBoundaries() {
        event(warehouseId, "day1", "2026-10-18T15:00:00Z", "SUCCEEDED", "80.00");
        event(warehouseId, "day2", "2026-10-19T14:59:59Z", "SUCCEEDED", "90.00");
        event(warehouseId, "pending", "2026-10-19T00:00:00Z", "PENDING", "1.00");
        event(warehouseId, "tomorrow", "2026-10-19T15:00:00Z", "SUCCEEDED", "10.00");
        var queries = new MetricQueryRepository(new NamedParameterJdbcTemplate(jdbc));
        var values = queries.aggregate(warehouseId, Instant.parse("2026-10-18T15:00:00Z"), Instant.parse("2026-10-19T15:00:00Z"),
                "Asia/Seoul", MetricSeries.Bucket.day, MetricSeries.Metric.AVG_FILL_RATE_PCT, null);
        assertThat(values).hasSize(1);
        assertThat(values.getFirst().at()).isEqualTo(Instant.parse("2026-10-18T15:00:00Z"));
        assertThat(values.getFirst().value()).isEqualByComparingTo("85");
        assertThat(values.getFirst().sampleCount()).isEqualTo(2);
    }

    @Test void simulationRangeUsesExclusiveCompositeCursorAndIncludesExactTimeBoundaries() {
        Instant from = Instant.parse("2026-09-30T00:00:00Z"), to = from.plusSeconds(1);
        em.getTransaction().begin();
        var warehouse = em.find(Warehouse.class, warehouseId);
        for (String id : List.of("a", "b", "c", "outside")) {
            Instant at = id.equals("outside") ? from.minusSeconds(1) : id.equals("c") ? to : from;
            em.persist(ItemEvent.builder().id(new ItemEventId(warehouseId + id, at)).warehouse(warehouse).status("SUCCEEDED").build());
        }
        em.getTransaction().commit();
        var repository = repositories.getRepository(ItemEventRepository.class);
        var first = repository.findRange(warehouseId, from, to, PageRequest.of(0, 1));
        assertThat(first).extracting(row -> row.getId().getEventId()).containsExactly(warehouseId + "a");
        var next = repository.findRangeAfter(warehouseId, from, to, from, warehouseId + "a", PageRequest.of(0, 10));
        assertThat(next).extracting(row -> row.getId().getEventId()).containsExactly(warehouseId + "b", warehouseId + "c");
    }

    @Test void latestProposalSelectionIsScopedAndForecastSumRejectsIncompleteInput() {
        UUID proposalId = UUID.randomUUID(), boxId = UUID.randomUUID();
        jdbc.update("insert into procurement_proposal (id, warehouse_id, target_month, generated_at) values (?, ?, '2026-11', ?)",
                proposalId, warehouseId, Timestamp.from(Instant.parse("2026-10-19T00:00:00Z")));
        jdbc.update("insert into box_spec (id, warehouse_id, code, version) values (?, ?, 'S-1', 0)", boxId, warehouseId);
        jdbc.update("insert into procurement_proposal_item (proposal_id, box_spec_id, forecast_usage_qty) values (?, ?, 0)", proposalId, boxId);
        assertThat(repositories.getRepository(ProcurementProposalRepository.class)
                .findFirstByWarehouseIdAndTargetMonthOrderByGeneratedAtDescIdDesc(warehouseId, "2026-11")).isPresent();
        assertThat(repositories.getRepository(ProcurementProposalRepository.class)
                .findByIdAndWarehouseId(proposalId, UUID.randomUUID())).isEmpty();
        var items = repositories.getRepository(ProcurementProposalItemRepository.class);
        assertThat(items.sumForecastUsageQty(proposalId)).isZero();
        jdbc.update("update procurement_proposal_item set forecast_usage_qty = null where proposal_id = ?", proposalId);
        assertThat(items.sumForecastUsageQty(proposalId)).isNull();
    }

    @Test void historySummaryCountsEachOrderOnceDespiteMultipleItemsAndEscapesSearchWildcards() {
        UUID supplier = UUID.randomUUID(), firstBox = UUID.randomUUID(), secondBox = UUID.randomUUID();
        jdbc.update("insert into supplier (id, warehouse_id, name) values (?, ?, '100% 포장')", supplier, warehouseId);
        for (UUID box : List.of(firstBox, secondBox)) jdbc.update("insert into box_spec (id, warehouse_id, version) values (?, ?, 0)", box, warehouseId);
        UUID received = order("PO-2026-1001", "RECEIVED", supplier);
        UUID pending = order("PO-2026-1002", "PENDING", supplier);
        UUID rejected = order("PO-2026-1003", "REJECTED", supplier);
        line(received, firstBox, 10, 180); line(received, secondBox, 20, 210);
        line(pending, firstBox, 5, 180); line(rejected, firstBox, 100, 999);
        jdbc.update("update purchase_order set approved_at = ?, received_at = ? where id = ?",
                Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")), Timestamp.from(Instant.parse("2026-10-05T12:00:00Z")), received);
        var queries = new PurchaseHistorySummaryRepository(new NamedParameterJdbcTemplate(jdbc));
        var totals = queries.summarize(warehouseId, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), "%");
        assertThat(totals.stream().mapToLong(PurchaseHistorySummaryRepository.StatusTotals::count).sum()).isEqualTo(3);
        assertThat(totals.stream().map(PurchaseHistorySummaryRepository.StatusTotals::amountKrw).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("6900");
        assertThat(totals.stream().filter(value -> value.status().equals("RECEIVED")).findFirst().orElseThrow().leadDays())
                .isEqualByComparingTo("4.5");
        assertThat(queries.summarize(UUID.randomUUID(), Instant.EPOCH, Instant.parse("2026-11-01T00:00:00Z"), null)).isEmpty();
        var service = new PurchaseOrderQueryService(repositories.getRepository(PurchaseOrderRepository.class),
                repositories.getRepository(PurchaseOrderItemRepository.class), queries,
                new WarehouseQueryGuard(repositories.getRepository(WarehouseRepository.class)), properties);
        var page = service.list(warehouseId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "%",
                PurchaseOrderStatus.PENDING, 0, 1, null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.counts().get("ALL")).isEqualTo(3);
        assertThat(page.summary().totalAmountKrw()).isEqualByComparingTo("6900");
        var history = service.list(warehouseId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "%",
                null, 0, 1, null, true);
        assertThat(history.items()).extracting(row -> row.status()).containsExactly(PurchaseOrderStatus.RECEIVED);
        assertThat(history.totalElements()).isEqualTo(1);
        assertThat(history.counts().get("ALL")).isEqualTo(1);
        assertThat(history.counts().get("PENDING")).isZero();
        assertThat(history.counts().get("REJECTED")).isZero();
        assertThat(history.summary().totalOrderCount()).isEqualTo(1);
        assertThat(history.summary().totalAmountKrw()).isEqualByComparingTo("6000");
        assertThat(history.summary().pendingCount()).isZero();
        assertThat(service.list(warehouseId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "%",
                PurchaseOrderStatus.PENDING, 0, 1, null, true).items()).isEmpty();
        assertThat(service.get(warehouseId, received).items()).hasSize(2);
        assertThatThrownBy(() -> service.get(warehouseId, UUID.randomUUID())).isInstanceOf(BusinessException.class);
    }

    @Test void boxListCountsIgnoreTabsButConnectedProductsExcludeInactiveProducts() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        jdbc.update("insert into box_spec (id, warehouse_id, code, is_active, is_custom, version) values (?, ?, 'S-1', true, false, 0)", first, warehouseId);
        jdbc.update("insert into box_spec (id, warehouse_id, code, is_active, is_custom, version) values (?, ?, 'M-1', false, true, 0)", second, warehouseId);
        for (boolean active : List.of(true, true, false)) jdbc.update(
                "insert into product (id, warehouse_id, matched_box_spec_id, is_active, version) values (?, ?, ?, ?, 0)",
                UUID.randomUUID(), warehouseId, first, active);
        var service = new BoxSpecService(repositories.getRepository(BoxSpecRepository.class), repositories.getRepository(WarehouseRepository.class),
                repositories.getRepository(SupplierRepository.class), repositories.getRepository(ProductRepository.class),
                repositories.getRepository(PurchaseOrderItemRepository.class), repositories.getRepository(ProcurementProposalItemRepository.class),
                repositories.getRepository(ItemEventRepository.class), mock(com.skala.chagok.domain.inventory.repository.InventoryMovementRepository.class),
                mock(com.skala.chagok.domain.product.service.ProductMatchingService.class),
                new com.skala.chagok.domain.box.service.BoxSizeClassifier(properties));
        var result = service.getBoxSpecs(warehouseId, null, true, null, 0, 20, null);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().linkedProductCount()).isEqualTo(2);
        assertThat(result.counts()).containsEntry("ALL", 2L).containsEntry("ACTIVE", 1L).containsEntry("CUSTOM", 1L);
        assertThat(result.summary().totalSpecCount()).isEqualTo(2);
        assertThat(service.getBoxSpec(warehouseId, first).linkedProductCount()).isEqualTo(2);
    }

    @Test void notificationPageIsRecipientScopedAndUnreadCountIgnoresCategoryFilter() {
        UUID userId = UUID.randomUUID(), other = UUID.randomUUID();
        jdbc.update("insert into app_user (id, warehouse_id, name) values (?, ?, '본인')", userId, warehouseId);
        jdbc.update("insert into app_user (id, warehouse_id, name) values (?, ?, '다른 사용자')", other, warehouseId);
        for (String category : List.of("INVENTORY", "AI")) jdbc.update(
                "insert into notification (id, recipient_id, category, message, created_at) values (?, ?, ?, '내용', ?)",
                UUID.randomUUID(), userId, category, Timestamp.from(Instant.parse("2026-10-19T00:00:00Z")));
        jdbc.update("insert into notification (id, recipient_id, category, message, created_at) values (?, ?, 'INVENTORY', '타인 알림', ?)",
                UUID.randomUUID(), other, Timestamp.from(Instant.parse("2026-10-20T00:00:00Z")));
        var users = mock(CurrentUserProvider.class);
        when(users.getCurrentUser()).thenReturn(em.find(AppUser.class, userId));
        var service = new NotificationQueryService(repositories.getRepository(NotificationRepository.class), users,
                new WarehouseQueryGuard(repositories.getRepository(WarehouseRepository.class)));
        var result = service.list(warehouseId, NotificationPageResponse.Category.INVENTORY, true, 0, 20, null);
        assertThat(result.items()).hasSize(1);
        assertThat(result.unreadCount()).isEqualTo(2);
        assertThat(result.items().getFirst().title()).isEqualTo("내용");
    }

    @Test void savedCostJsonIsReadAsTypedResultAndJobsAreTenantScoped() {
        UUID jobId = UUID.randomUUID(), costId = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-19T00:00:00Z");
        jdbc.update("insert into analysis_job (id, warehouse_id, target_month, job_type, status, created_at, version) "
                + "values (?, ?, '2026-11', 'PROCUREMENT_AND_COST', 'FAILED', ?, 0)", jobId, warehouseId, Timestamp.from(at));
        String snapshot = """
                {"packagingCostKrw":13350000,"packagingSavingsKrw":3420000,"purchaseCostKrw":10566000,
                 "previousMonth":null,"previousYear":null,"items":[],"insights":["저장된 근거"]}
                """;
        jdbc.update("insert into cost_forecast (id, warehouse_id, target_month, job_id, generated_at, data_as_of, result) "
                + "values (?, ?, '2026-11', ?, ?, ?, ? format json)", costId, warehouseId, jobId,
                Timestamp.from(at.plusSeconds(60)), Timestamp.from(at), snapshot);
        var cost = repositories.getRepository(CostForecastRepository.class)
                .findFirstByWarehouseIdAndTargetMonthOrderByGeneratedAtDescIdDesc(warehouseId, "2026-11").orElseThrow();
        assertThat(cost.getResult()).isInstanceOf(CostForecastResult.class);
        assertThat(cost.getResult().purchaseCostKrw()).isEqualTo(10566000);
        assertThat(cost.getResult().insights()).containsExactly("저장된 근거");
        var jobs = repositories.getRepository(AnalysisJobRepository.class);
        assertThat(jobs.findByIdAndWarehouseId(jobId, UUID.randomUUID())).isEmpty();
        assertThat(jobs.findFirstByWarehouseIdAndTargetMonthAndTypeOrderByCreatedAtDescIdDesc(warehouseId, "2026-11",
                AnalysisJob.Type.PROCUREMENT_AND_COST)).hasValueSatisfying(job -> assertThat(job.getStatus()).isEqualTo(AnalysisJob.Status.FAILED));
    }

    private void event(UUID warehouse, String id, String at, String status, String fillRate) {
        jdbc.update("insert into item_event (warehouse_id, event_id, occurred_at, status, fill_rate_pct) values (?, ?, ?, ?, ?)",
                warehouse, warehouseId + id, Timestamp.from(Instant.parse(at)), status, fillRate == null ? null : new BigDecimal(fillRate));
    }
    private UUID order(String no, String status, UUID supplier) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into purchase_order (id, warehouse_id, po_no, status, supplier_id, created_at, version) values (?, ?, ?, ?, ?, ?, 0)",
                id, warehouseId, no, status, supplier, Timestamp.from(Instant.parse("2026-10-19T00:00:00Z")));
        return id;
    }
    private void line(UUID order, UUID box, int qty, int price) {
        jdbc.update("insert into purchase_order_item (purchase_order_id, box_spec_id, qty, unit_price_krw) values (?, ?, ?, ?)", order, box, qty, price);
    }
}
