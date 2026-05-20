package com.repoinsight.generator;

import com.repoinsight.analyzer.model.BusinessFlow;
import com.repoinsight.analyzer.model.IntegrationPoint;
import com.repoinsight.analyzer.model.MethodCallNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class DocumentationGeneratorTest {

    private DocumentationGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new DocumentationGenerator();
    }

    @Test
    @DisplayName("Business logic Markdown includes all flow names")
    void renderBusinessLogic_allFlowsPresent() {
        BusinessFlow f1 = flow("PlaceOrderFlow", "POST /api/orders");
        BusinessFlow f2 = flow("CancelOrderFlow", "DELETE /api/orders/{id}");

        String md = generator.renderBusinessLogic(List.of(f1, f2), "https://github.com/org/repo");

        assertThat(md).contains("PlaceOrderFlow");
        assertThat(md).contains("CancelOrderFlow");
        assertThat(md).contains("POST /api/orders");
        assertThat(md).contains("DELETE /api/orders/{id}");
    }

    @Test
    @DisplayName("Business logic Markdown has a Table of Contents section")
    void renderBusinessLogic_hasToc() {
        String md = generator.renderBusinessLogic(List.of(flow("MyFlow", "GET /")),
                "https://github.com/org/repo");
        assertThat(md).contains("## Table of Contents");
        assertThat(md).contains("[MyFlow]");
    }

    @Test
    @DisplayName("Empty flows list renders header only without errors")
    void renderBusinessLogic_emptyFlows_noError() {
        String md = generator.renderBusinessLogic(List.of(), "https://github.com/org/repo");
        assertThat(md).startsWith("# Business Logic Documentation");
    }

    @Test
    @DisplayName("Method hierarchy Markdown contains entry point method signatures")
    void renderMethodHierarchy_containsSignatures() {
        MethodCallNode root = MethodCallNode.builder()
                .className("OrderController")
                .methodSignature("createOrder(CreateOrderRequest)")
                .layer("CONTROLLER")
                .integrationKind(MethodCallNode.IntegrationKind.NONE)
                .depth(0)
                .children(List.of(
                        MethodCallNode.builder()
                                .className("OrderService")
                                .methodSignature("createOrder(OrderDto)")
                                .layer("SERVICE")
                                .integrationKind(MethodCallNode.IntegrationKind.DB)
                                .integrationTarget("PostgreSQL")
                                .depth(1)
                                .children(List.of())
                                .build()
                ))
                .build();

        String md = generator.renderMethodHierarchy(List.of(root), "https://github.com/org/repo");

        assertThat(md).contains("createOrder(CreateOrderRequest)");
        assertThat(md).contains("OrderService");
        assertThat(md).contains("PostgreSQL");
        assertThat(md).contains("[DB → PostgreSQL]");
    }

    @Test
    @DisplayName("Integration topology groups by category")
    void renderIntegrations_groupsByCategory() {
        IntegrationPoint db = IntegrationPoint.builder()
                .category("DB").name("PostgreSQL (JPA)").classRef("OrderRepo")
                .methodRef("save").detectionPattern("@Repository").direction("WRITE").build();
        IntegrationPoint kafka = IntegrationPoint.builder()
                .category("KAFKA").name("Kafka: order.created").classRef("Publisher")
                .methodRef("publish").detectionPattern("KafkaTemplate.send()").direction("PRODUCE").build();

        String md = generator.renderIntegrations(List.of(db, kafka), "https://github.com/org/repo");

        assertThat(md).contains("Database (JDBC / JPA)");
        assertThat(md).contains("Message Broker (Kafka)");
        assertThat(md).contains("PostgreSQL (JPA)");
        assertThat(md).contains("Kafka: order.created");
    }

    @Test
    @DisplayName("Integration topology skips empty categories silently")
    void renderIntegrations_emptyCategory_skipped() {
        String md = generator.renderIntegrations(List.of(), "https://github.com/org/repo");
        assertThat(md).doesNotContain("| ");  // no table rows
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private BusinessFlow flow(String name, String trigger) {
        return BusinessFlow.builder()
                .name(name)
                .trigger(trigger)
                .description("A flow description")
                .steps(List.of("step 1", "step 2"))
                .invariants(List.of("invariant A"))
                .sideEffects(List.of("event emitted"))
                .build();
    }
}
