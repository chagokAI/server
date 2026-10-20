package com.skala.chagok.domain;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.event.entity.ItemEvent;
import com.skala.chagok.domain.notification.entity.Notification;
import com.skala.chagok.domain.procurement.entity.ProcurementProposal;
import com.skala.chagok.domain.procurement.entity.ProcurementProposalItem;
import com.skala.chagok.domain.product.entity.Product;
import com.skala.chagok.domain.purchase.entity.PurchaseOrder;
import com.skala.chagok.domain.purchase.entity.PurchaseOrderItem;
import com.skala.chagok.domain.supplier.entity.Supplier;
import com.skala.chagok.domain.user.entity.AppUser;
import com.skala.chagok.domain.warehouse.entity.Warehouse;

import java.io.StringWriter;

import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.dialect.PostgreSQLDialect;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntityMappingTests {

    @Test
    void bootstrapsAllEntitiesAndGeneratesPostgreSqlSchemaWithoutDatabase() {
        StringWriter schema = new StringWriter();
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", PostgreSQLDialect.class.getName())
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("jakarta.persistence.schema-generation.database.action", "none")
                .applySetting("jakarta.persistence.schema-generation.scripts.action", "create")
                .applySetting("jakarta.persistence.schema-generation.scripts.create-target", schema)
                .build();

        try {
            MetadataSources sources = new MetadataSources(registry);
            for (Class<?> entity : new Class<?>[]{
                    Warehouse.class, AppUser.class, Supplier.class, BoxSpec.class,
                    Product.class, ProcurementProposal.class, ProcurementProposalItem.class,
                    PurchaseOrder.class, PurchaseOrderItem.class, ItemEvent.class, Notification.class
            }) {
                sources.addAnnotatedClass(entity);
            }

            try (SessionFactory sessionFactory = sources.buildMetadata().buildSessionFactory()) {
                assertThat(sessionFactory.getMetamodel().getEntities()).hasSize(11);
                assertThat(schema.toString())
                        .contains("embedding vector(384)", "summary jsonb", "placement jsonb")
                        .contains("primary key (box_spec_id, proposal_id)")
                        .contains("primary key (box_spec_id, purchase_order_id)")
                        .contains("primary key (occurred_at, event_id)");
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
