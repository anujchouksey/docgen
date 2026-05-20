package com.repoinsight.agent;

import com.repoinsight.analyzer.model.BusinessFlow;
import com.repoinsight.github.model.GitHubFile;
import com.repoinsight.llm.LlmClient;
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
    private LlmClient llmClient;

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
    @DisplayName("Returns parsed business flows when LLM responds with valid data")
    void analyse_validResponse_returnsFlows() {
        when(llmClient.runAgentWithJsonOutput(anyString(), anyString(), anyString(), any()))
                .thenReturn(buildWrapper());

        List<BusinessFlow> result = agent.analyse(sampleFiles);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("TestFlow");
    }

    @Test
    @DisplayName("Empty file list returns empty flow list")
    void analyse_emptyFiles_returnsEmpty() {
        BusinessLogicAgent.FlowsWrapper emptyWrapper = new BusinessLogicAgent.FlowsWrapper();
        emptyWrapper.setFlows(List.of());
        when(llmClient.runAgentWithJsonOutput(anyString(), anyString(), anyString(), any()))
                .thenReturn(emptyWrapper);

        List<BusinessFlow> result = agent.analyse(List.of());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("LLM client exception propagates as IllegalStateException")
    void analyse_llmThrows_propagates() {
        when(llmClient.runAgentWithJsonOutput(anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("LLM returned invalid JSON"));

        assertThatThrownBy(() -> agent.analyse(sampleFiles))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Context builder includes Service files and excludes test files")
    void contextBuilding_prioritisesServiceClasses() {
        List<GitHubFile> files = List.of(
                GitHubFile.builder().path("OrderService.java").content("service").sha("s1").sizeBytes(7).build(),
                GitHubFile.builder().path("OrderRepository.java").content("repo").sha("s2").sizeBytes(4).build(),
                GitHubFile.builder().path("OrderServiceTest.java").content("test").sha("s3").sizeBytes(4).build()
        );

        when(llmClient.runAgentWithJsonOutput(anyString(), anyString(), anyString(), any()))
                .thenReturn(buildWrapper());

        agent.analyse(files);

        verify(llmClient).runAgentWithJsonOutput(anyString(), anyString(),
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
