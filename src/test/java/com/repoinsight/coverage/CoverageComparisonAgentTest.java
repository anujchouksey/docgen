package com.repoinsight.coverage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoinsight.coverage.agent.CoverageComparisonAgent;
import com.repoinsight.llm.LlmClient;
import com.repoinsight.coverage.model.ClassCoverageReport;
import com.repoinsight.coverage.model.CoverageReport;
import com.repoinsight.coverage.model.CoverageStatus;
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
class CoverageComparisonAgentTest {

    @Mock private LlmClient llmClient;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks private CoverageComparisonAgent agent;

    private List<GitHubFile> devFiles;
    private List<GitHubFile> qaFiles;

    @BeforeEach
    void setUp() {
        devFiles = List.of(
                javaFile("src/main/java/com/example/OrderService.java",
                        "public class OrderService { void createOrder(){} void cancelOrder(){} }"),
                javaFile("src/main/java/com/example/OrderDto.java",
                        "@Data public class OrderDto { private String id; private String status; }")
        );
        qaFiles = List.of(
                javaFile("src/test/java/com/example/OrderServiceTest.java",
                        "@Test void testCreateOrder() { ... }")
        );
    }

    @Test
    @DisplayName("Empty dev files returns report with empty classes list")
    void compare_emptyDevFiles_emptyClasses() {
        when(llmClient.runAgentWithJsonOutput(anyString(), anyString(), anyString(), any()))
                .thenReturn(wrapper(List.of()));
        when(llmClient.runAgent(anyString(), anyString(), anyString()))
                .thenReturn("Coverage score is 0%.");

        CoverageReport report = agent.compare(List.of(), qaFiles,
                "https://github.com/o/dev", "https://github.com/o/qa",
                "main", "main", "job-1", "ALL");

        assertThat(report.getClasses()).isEmpty();
        assertThat(report.getSummary().getCoverageScore()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Empty QA files — all non-NOT_NEEDED classes should be MISSED")
    void compare_emptyQaFiles_allMissed() {
        ClassCoverageReport orderSvc = ClassCoverageReport.builder()
                .devClass("OrderService").devFile("OrderService.java").layer("SERVICE")
                .status(CoverageStatus.MISSED).coveredMethods(List.of())
                .missedMethods(List.of("createOrder", "cancelOrder"))
                .missingScenarios(List.of("create order happy path"))
                .relevantQaFiles(List.of()).build();

        when(llmClient.runAgentWithJsonOutput(anyString(), anyString(), anyString(), any()))
                .thenReturn(wrapper(List.of(orderSvc)));
        when(llmClient.runAgent(anyString(), anyString(), anyString()))
                .thenReturn("No QA tests found. Coverage is 0%.");

        CoverageReport report = agent.compare(devFiles, List.of(),
                "https://github.com/o/dev", "https://github.com/o/qa",
                "main", "main", "job-2", "ALL");

        assertThat(report.getClasses()).anyMatch(c -> c.getStatus() == CoverageStatus.MISSED);
        assertThat(report.getSummary().getCoverageScore()).isLessThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("Coverage score is 100% when all testable classes are COVERED")
    void computeSummary_allCovered_score100() {
        ClassCoverageReport covered = ClassCoverageReport.builder()
                .devClass("OrderService").devFile("OrderService.java").layer("SERVICE")
                .status(CoverageStatus.COVERED).coveredMethods(List.of("createOrder"))
                .missedMethods(List.of()).missingScenarios(List.of()).relevantQaFiles(List.of()).build();
        ClassCoverageReport notNeeded = ClassCoverageReport.builder()
                .devClass("OrderDto").devFile("OrderDto.java").layer("OTHER")
                .status(CoverageStatus.NOT_NEEDED).coveredMethods(List.of())
                .missedMethods(List.of()).missingScenarios(List.of()).relevantQaFiles(List.of()).build();

        when(llmClient.runAgentWithJsonOutput(anyString(), anyString(), anyString(), any()))
                .thenReturn(wrapper(List.of(covered, notNeeded)));
        when(llmClient.runAgent(anyString(), anyString(), anyString()))
                .thenReturn("Excellent coverage.");

        CoverageReport report = agent.compare(devFiles, qaFiles,
                "https://github.com/o/dev", "https://github.com/o/qa",
                "main", "main", "job-3", "ALL");

        assertThat(report.getSummary().getCoverageScore()).isEqualTo(100.0);
        assertThat(report.getSummary().getNotNeeded()).isEqualTo(1);
    }

    @Test
    @DisplayName("Coverage score formula: (covered + partial*0.5) / testable")
    void computeSummary_mixedStatuses_correctScore() {
        var classes = List.of(
                cls("A", CoverageStatus.COVERED),
                cls("B", CoverageStatus.COVERED),
                cls("C", CoverageStatus.PARTIAL),
                cls("D", CoverageStatus.MISSED),
                cls("E", CoverageStatus.NOT_NEEDED)
        );
        when(llmClient.runAgentWithJsonOutput(anyString(), anyString(), anyString(), any()))
                .thenReturn(wrapper(classes));
        when(llmClient.runAgent(anyString(), anyString(), anyString())).thenReturn("Summary.");

        CoverageReport report = agent.compare(devFiles, qaFiles,
                "https://github.com/o/dev", "https://github.com/o/qa",
                "main", "main", "job-4", "ALL");

        // testable = 4 (E is not needed), score = (2 + 0.5) / 4 * 100 = 62.5
        assertThat(report.getSummary().getCoverageScore()).isEqualTo(62.5);
    }

    @Test
    @DisplayName("Layer focus SERVICE filters out non-service files before sending to Claude")
    void compare_layerFocusService_filtersFiles() {
        List<GitHubFile> mixed = List.of(
                javaFile("src/main/java/OrderService.java", "class OrderService{}"),
                javaFile("src/main/java/OrderController.java", "class OrderController{}"),
                javaFile("src/main/java/OrderRepository.java", "class OrderRepository{}")
        );
        when(llmClient.runAgentWithJsonOutput(anyString(), anyString(),
                argThat((String ctx) -> ctx.contains("OrderService") && !ctx.contains("OrderController")),
                any())).thenReturn(wrapper(List.of()));
        when(llmClient.runAgent(anyString(), anyString(), anyString())).thenReturn("Summary.");

        agent.compare(mixed, qaFiles, "https://github.com/o/dev", "https://github.com/o/qa",
                "main", "main", "job-5", "SERVICE");

        verify(llmClient).runAgentWithJsonOutput(anyString(), anyString(),
                argThat((String ctx) -> ctx.contains("OrderService.java") && !ctx.contains("OrderController.java")),
                any());
    }

    @Test
    @DisplayName("Dev files in batches of 20 result in correct number of Claude calls")
    void compare_60DevFiles_3BatchCalls() {
        List<GitHubFile> sixtyFiles = java.util.stream.IntStream.rangeClosed(1, 60)
                .mapToObj(i -> javaFile("Service" + i + ".java", "class Service" + i + " {}"))
                .toList();
        when(llmClient.runAgentWithJsonOutput(anyString(), anyString(), anyString(), any()))
                .thenReturn(wrapper(List.of()));
        when(llmClient.runAgent(anyString(), anyString(), anyString())).thenReturn("Summary.");

        agent.compare(sixtyFiles, qaFiles, "https://github.com/o/dev", "https://github.com/o/qa",
                "main", "main", "job-6", "ALL");

        // 60 files / 20 per batch = 3 batch calls + 1 executive summary call
        verify(llmClient, times(3)).runAgentWithJsonOutput(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Executive summary is always generated even when classes list is empty")
    void compare_emptyResult_executiveSummaryStillGenerated() {
        when(llmClient.runAgentWithJsonOutput(anyString(), anyString(), anyString(), any()))
                .thenReturn(wrapper(List.of()));
        when(llmClient.runAgent(anyString(), anyString(), anyString()))
                .thenReturn("No classes analysed.");

        CoverageReport report = agent.compare(devFiles, qaFiles,
                "https://github.com/o/dev", "https://github.com/o/qa",
                "main", "main", "job-7", "ALL");

        assertThat(report.getExecutiveSummary()).isNotBlank();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private GitHubFile javaFile(String path, String content) {
        return GitHubFile.builder().path(path).content(content).sha("sha-x").sizeBytes(content.length()).build();
    }

    private CoverageComparisonAgent.BatchResult wrapper(List<ClassCoverageReport> classes) {
        var w = new CoverageComparisonAgent.BatchResult();
        w.setClasses(classes);
        return w;
    }

    private ClassCoverageReport cls(String name, CoverageStatus status) {
        return ClassCoverageReport.builder()
                .devClass(name).devFile(name + ".java").layer("SERVICE").status(status)
                .coveredMethods(List.of()).missedMethods(List.of())
                .missingScenarios(List.of()).relevantQaFiles(List.of()).build();
    }
}
