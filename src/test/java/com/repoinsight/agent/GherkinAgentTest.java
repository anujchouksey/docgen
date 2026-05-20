package com.repoinsight.agent;

import com.repoinsight.analyzer.model.BusinessFlow;
import com.repoinsight.analyzer.model.IntegrationPoint;
import com.repoinsight.llm.LlmClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GherkinAgentTest {

    @Mock
    private LlmClient llmClient;

    @InjectMocks
    private GherkinAgent agent;

    private static final BusinessFlow ORDER_FLOW = BusinessFlow.builder()
            .name("PlaceOrderFlow")
            .trigger("POST /api/orders")
            .description("Customer places a new order")
            .steps(List.of("validate cart", "charge payment", "persist order", "emit event"))
            .invariants(List.of("cart must not be empty", "payment amount > 0"))
            .sideEffects(List.of("OrderPlaced event on Kafka", "confirmation email"))
            .build();

    private static final IntegrationPoint DB = IntegrationPoint.builder()
            .category("DB").name("PostgreSQL (JPA)").classRef("OrderRepository")
            .methodRef("save").detectionPattern("@Repository").direction("WRITE").build();

    private static final IntegrationPoint KAFKA = IntegrationPoint.builder()
            .category("KAFKA").name("Kafka topic: order.created").classRef("OrderEventPublisher")
            .methodRef("publish").detectionPattern("KafkaTemplate.send()").direction("PRODUCE").build();

    @Test
    @DisplayName("Empty flows list returns empty list without calling Claude")
    void generateFeatures_emptyFlows_returnsEmpty() {
        List<String> result = agent.generateFeatures(List.of(), List.of(DB));

        assertThat(result).isEmpty();
        verifyNoInteractions(llmClient);
    }

    @Test
    @DisplayName("One flow produces one feature file")
    void generateFeatures_oneFlow_oneFeature() {
        when(llmClient.runAgent(anyString(), anyString(), anyString()))
                .thenReturn("Feature: PlaceOrderFlow\n\n  Scenario: ...");

        List<String> result = agent.generateFeatures(List.of(ORDER_FLOW), List.of(DB));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).startsWith("Feature:");
    }

    @Test
    @DisplayName("Feature generation calls Claude once per flow")
    void generateFeatures_callsClaudePerFlow() {
        BusinessFlow flow2 = BusinessFlow.builder()
                .name("CancelOrderFlow").trigger("DELETE /api/orders/{id}").description("d")
                .steps(List.of()).invariants(List.of()).sideEffects(List.of()).build();

        when(llmClient.runAgent(anyString(), anyString(), anyString()))
                .thenReturn("Feature: SomeFlow\n\n  Scenario: placeholder");

        agent.generateFeatures(List.of(ORDER_FLOW, flow2), List.of(DB, KAFKA));

        verify(llmClient, times(2)).runAgent(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("User prompt includes integration point categories")
    void generateFeatures_userPromptContainsIntegrations() {
        when(llmClient.runAgent(anyString(), anyString(), anyString()))
                .thenReturn("Feature: PlaceOrderFlow");

        agent.generateFeatures(List.of(ORDER_FLOW), List.of(DB, KAFKA));

        verify(llmClient).runAgent(
                anyString(),
                argThat((String prompt) -> prompt.contains("DB") && prompt.contains("KAFKA")),
                anyString()
        );
    }

    @Test
    @DisplayName("Claude exception propagates for the failing flow")
    void generateFeatures_claudeThrows_propagates() {
        when(llmClient.runAgent(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("Claude timeout"));

        assertThatThrownBy(() -> agent.generateFeatures(List.of(ORDER_FLOW), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Claude timeout");
    }

    @Test
    @DisplayName("Flow with no integrations still triggers a Claude call")
    void generateFeatures_noIntegrations_stillCallsClaude() {
        BusinessFlow simpleFlow = BusinessFlow.builder()
                .name("HealthCheckFlow").trigger("GET /health").description("simple")
                .steps(List.of()).invariants(List.of()).sideEffects(List.of()).build();

        when(llmClient.runAgent(anyString(), anyString(), anyString())).thenReturn("Feature: HealthCheckFlow");

        List<String> result = agent.generateFeatures(List.of(simpleFlow), List.of());

        assertThat(result).hasSize(1);
        verify(llmClient, times(1)).runAgent(anyString(), anyString(), anyString());
    }
}
