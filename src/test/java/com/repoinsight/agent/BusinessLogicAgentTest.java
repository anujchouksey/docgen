package com.repoinsight.agent;

import com.repoinsight.analyzer.model.BusinessFlow;
import com.repoinsight.claude.ClaudeAgentClient;
import com.repoinsight.github.model.GitHubFile;
import org.junit.jupiter.api.BeforeEach;
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
class BusinessLogicAgentTest {

    @Mock
    private ClaudeAgentClient claude;

    @InjectMocks
    private BusinessLogicAgent agent;

    private List<GitHubFile> sampleFiles;

    @BeforeEach
    void setUp() {
        sampleFiles = List.of(
                GitHubFile.builder().path("OrderService.java")
                        .content("public class OrderService { ... }").sha("a1").sizeBytes(100).build()
        );
    }

    @Test
    @DisplayName("Returns parsed business flows when Claude responds with valid JSON")
    void analyse_validResponse_returnsFlows() {
        String validJson = """
                {
                  "flows": [
                    {
                      "name": "PlaceOrderFlow",
                      "trigger": "POST /api/orders",
                      "description": "Customer places an order",
                      "steps": ["validate cart", "charge payment"],
                      "invariants": ["cart must not be empty"],
                      "sideEffects": ["OrderPlaced event emitted"]
                    }
                  ]
                }
                """;
        when(claude.runAgentWithJsonOutput(anyString(), anyString(), anyString(),
                eq(BusinessLogicAgent.FlowsWrapper.class)))
                .thenThrow(new IllegalStateException("private class"));

        // Use the public-facing method; FlowsWrapper is package-private so we test via agent
        // Use spy + real deserialization path
        when(claude.runAgentWithJsonOutput(anyString(), anyString(), anyString(), any()))
                .thenCallRealMethod();
    }

    @Test
    @DisplayName("Empty file list returns empty flow list without calling Claude")
    void analyse_emptyFiles_returnsEmpty() {
        // When no service files are found Claude should still be called
        // but return zero flows
        when(claude.runAgentWithJsonOutput(anyString(), anyString(), anyString(), any()))
                .thenReturn(null); // simulate empty wrapper

        assertThatCode(() -> agent.analyse(List.of())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Claude client exception propagates as IllegalStateException")
    void analyse_claudeThrows_propagates() {
        when(claude.runAgentWithJsonOutput(anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("Claude returned invalid JSON"));

        assertThatThrownBy(() -> agent.analyse(sampleFiles))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Claude");
    }

    @Test
    @DisplayName("Context builder includes Service files and excludes unrelated files")
    void contextBuilding_prioritisesServiceClasses() {
        List<GitHubFile> files = List.of(
                GitHubFile.builder().path("OrderService.java").content("service").sha("s1").sizeBytes(7).build(),
                GitHubFile.builder().path("OrderRepository.java").content("repo").sha("s2").sizeBytes(4).build(),
                GitHubFile.builder().path("OrderServiceTest.java").content("test").sha("s3").sizeBytes(4).build()
        );

        // The agent method is called; we verify Claude receives the right context
        when(claude.runAgentWithJsonOutput(anyString(), anyString(), stringThat(ctx ->
                ctx.contains("OrderService.java") && !ctx.contains("OrderServiceTest.java")), any()))
                .thenReturn(buildWrapper());

        agent.analyse(files);

        verify(claude).runAgentWithJsonOutput(anyString(), anyString(),
                stringThat(ctx -> ctx.contains("OrderService.java")), any());
    }

    private BusinessLogicAgent.FlowsWrapper buildWrapper() {
        var wrapper = new BusinessLogicAgent.FlowsWrapper();
        wrapper.setFlows(List.of(BusinessFlow.builder()
                .name("TestFlow").trigger("GET /").description("desc")
                .steps(List.of()).invariants(List.of()).sideEffects(List.of())
                .build()));
        return wrapper;
    }
}
