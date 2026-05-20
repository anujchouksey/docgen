package com.repoinsight.coverage.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoinsight.llm.LlmClient;
import com.repoinsight.coverage.model.ClassCoverageReport;
import com.repoinsight.coverage.model.CoverageReport;
import com.repoinsight.coverage.model.CoverageStatus;
import com.repoinsight.coverage.model.CoverageSummary;
import com.repoinsight.github.model.GitHubFile;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Core AI agent for dev-vs-QA coverage comparison.
 *
 * Strategy: process dev classes in batches of ~20 at a time, passing the full
 * QA file set as a prompt-cached context block.  This keeps individual calls
 * within the context window while exploiting caching for the (large) QA corpus.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CoverageComparisonAgent {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    private static final int DEV_BATCH_SIZE = 20;

    private static final String SYSTEM_PROMPT = """
            You are a senior QA architect performing coverage gap analysis.

            You will be given:
            1. A set of Java dev source files (the implementation to be tested)
            2. A set of QA test files (JUnit, TestNG, Cucumber .feature files, REST-Assured, etc.)

            Your task: for EACH dev class, determine coverage status and produce a structured report.

            ## Coverage Status Rules

            COVERED   — Every significant public method has at least one corresponding test that:
                         - exercises the happy path
                         - covers at least one failure/edge case

            PARTIAL   — One or more of the following:
                         - Some public methods have no test at all
                         - Tests exist but only cover the happy path (no failure, boundary, or
                           integration failure scenarios)
                         - Integration points (DB, Kafka, HTTP, cache) are untested in failure mode
                         - A Cucumber/Gherkin feature exists but is missing @edge-case/@db/@kafka
                           scenario tags

            MISSED    — No QA file semantically references this class or its behaviour.
                         Structural name-match alone is NOT sufficient; the test must actually
                         exercise the class's logic.

            NOT_NEEDED — Class is purely structural with no testable business logic:
                         - DTOs / POJOs (only getters/setters/equals/hashCode)
                         - @Configuration classes with only @Bean methods
                         - Exception classes extending RuntimeException
                         - Generated mappers (MapStruct, Lombok @Builder)
                         - Application entry points (main())

            ## Output Schema (JSON only, no markdown fences)

            {
              "classes": [
                {
                  "devClass": "OrderService",
                  "devFile": "src/main/java/.../OrderService.java",
                  "layer": "SERVICE",
                  "status": "PARTIAL",
                  "coveredMethods": ["createOrder", "getOrderById"],
                  "missedMethods": ["cancelOrder", "updateOrderStatus"],
                  "missingScenarios": [
                    "cancel order when shipment already dispatched",
                    "DB constraint violation on duplicate order ID",
                    "Kafka producer timeout on OrderCreated event"
                  ],
                  "relevantQaFiles": ["OrderServiceTest.java", "order_placement.feature"],
                  "explanation": "2 of 4 methods have tests. cancelOrder and updateOrderStatus have no coverage. The existing tests only cover happy paths — no DB failure, Kafka timeout, or concurrent update scenarios exist.",
                  "implementationNotes": "OrderService.createOrder() calls InventoryClient.reserve(), then PaymentGateway.charge(), then orderRepository.save(), then publishes OrderCreated to Kafka. The entire operation is @Transactional. Rollback happens if any step fails.",
                  "suggestedGherkin": "  @missed\\n  Scenario: Cancel order after shipment dispatched\\n    Given an order 'ORD-001' with status SHIPPED\\n    When a cancellation is requested for 'ORD-001'\\n    Then the response status is 409\\n    And the error message contains \\"already shipped\\""
                }
              ]
            }

            ## Important
            - layer must be one of: SERVICE, CONTROLLER, REPOSITORY, COMPONENT, CLIENT, CONSUMER, OTHER
            - suggestedGherkin must be valid Gherkin (indented with 2 spaces, use realistic data)
            - implementationNotes must explain WHAT the class does, not HOW to test it
            - If a QA file partially covers a class, name it in relevantQaFiles even if status is MISSED
            - Be precise: do not mark as COVERED if edge cases are absent
            """;

    public CoverageReport compare(List<GitHubFile> devFiles, List<GitHubFile> qaFiles,
                                  String devRepoUrl, String qaRepoUrl,
                                  String devBranch, String qaBranch,
                                  String jobId, String layerFocus) {
        long start = System.currentTimeMillis();
        log.info("CoverageComparisonAgent: {} dev files vs {} QA files", devFiles.size(), qaFiles.size());

        String qaContext = buildQaContext(qaFiles);
        List<ClassCoverageReport> allClasses = new ArrayList<>();

        // Filter by layer if requested
        List<GitHubFile> filteredDev = filterByLayer(devFiles, layerFocus);

        // Batch dev files to stay within context limits
        List<List<GitHubFile>> batches = partition(filteredDev, DEV_BATCH_SIZE);
        log.info("Processing {} batches of dev files", batches.size());

        for (int i = 0; i < batches.size(); i++) {
            log.info("Processing batch {}/{}", i + 1, batches.size());
            List<GitHubFile> batch = batches.get(i);
            String devContext = buildDevContext(batch);
            String fullContext = qaContext + "\n\n" + devContext;

            String userPrompt = """
                    Analyse the %d dev classes in the DEV SOURCES section against the QA tests in the QA FILES section.
                    Return the JSON array of ClassCoverageReport objects.
                    Layer focus: %s.
                    """.formatted(batch.size(), layerFocus);

            try {
                BatchResult result = llmClient.runAgentWithJsonOutput(
                        SYSTEM_PROMPT, userPrompt, fullContext, BatchResult.class);
                if (result != null && result.getClasses() != null) {
                    allClasses.addAll(result.getClasses());
                }
            } catch (Exception e) {
                log.error("Batch {} failed: {}", i + 1, e.getMessage());
            }
        }

        // Executive summary
        String executiveSummary = generateExecutiveSummary(allClasses, devRepoUrl, qaRepoUrl);

        CoverageSummary summary = computeSummary(allClasses);
        long durationMs = System.currentTimeMillis() - start;

        return CoverageReport.builder()
                .jobId(jobId)
                .devRepoUrl(devRepoUrl)
                .qaRepoUrl(qaRepoUrl)
                .devBranch(devBranch)
                .qaBranch(qaBranch)
                .generatedAt(Instant.now())
                .summary(summary)
                .classes(allClasses)
                .executiveSummary(executiveSummary)
                .devFilesAnalysed(devFiles.size())
                .qaFilesAnalysed(qaFiles.size())
                .durationMs(durationMs)
                .build();
    }

    private String generateExecutiveSummary(List<ClassCoverageReport> classes,
                                             String devRepoUrl, String qaRepoUrl) {
        CoverageSummary s = computeSummary(classes);
        String classStats = """
                Dev: %s | QA: %s | Score: %.1f%%
                Total: %d | Covered: %d | Partial: %d | Missed: %d | Not Needed: %d
                Top missed: %s
                """.formatted(devRepoUrl, qaRepoUrl, s.getCoverageScore(),
                s.getTotalDevClasses(), s.getCovered(), s.getPartial(),
                s.getNotCovered(), s.getNotNeeded(),
                classes.stream().filter(c -> c.getStatus() == CoverageStatus.MISSED)
                        .map(ClassCoverageReport::getDevClass).limit(5).toList());

        return llmClient.runAgent(
                "You are a QA manager writing a concise executive summary of a coverage analysis report. " +
                "Write 3–5 sentences. Be specific about which layers have the worst coverage and what the " +
                "biggest risks are. Mention the coverage score. No bullet points — flowing prose.",
                "Write an executive summary for this coverage analysis:\n\n" + classStats,
                classStats);
    }

    private CoverageSummary computeSummary(List<ClassCoverageReport> classes) {
        int covered = 0, partial = 0, missed = 0, notNeeded = 0;
        for (ClassCoverageReport c : classes) {
            switch (c.getStatus()) {
                case COVERED -> covered++;
                case PARTIAL -> partial++;
                case MISSED -> missed++;
                case NOT_NEEDED -> notNeeded++;
            }
        }
        int testable = classes.size() - notNeeded;
        double score = testable == 0 ? 0
                : Math.round((covered + partial * 0.5) / testable * 1000.0) / 10.0;

        return CoverageSummary.builder()
                .totalDevClasses(classes.size())
                .covered(covered).partial(partial).notCovered(missed).notNeeded(notNeeded)
                .coverageScore(score)
                .build();
    }

    private List<GitHubFile> filterByLayer(List<GitHubFile> files, String layerFocus) {
        if ("ALL".equals(layerFocus)) return files;
        String hint = switch (layerFocus) {
            case "SERVICE" -> "Service";
            case "CONTROLLER" -> "Controller";
            case "REPOSITORY" -> "Repository";
            default -> null;
        };
        if (hint == null) return files;
        return files.stream().filter(f -> f.getPath().contains(hint)).toList();
    }

    private String buildQaContext(List<GitHubFile> qaFiles) {
        StringBuilder sb = new StringBuilder("=== QA FILES (test suite) ===\n\n");
        qaFiles.forEach(f -> sb.append("--- ").append(f.getPath()).append(" ---\n")
                .append(f.getContent()).append("\n\n"));
        return sb.toString();
    }

    private String buildDevContext(List<GitHubFile> devFiles) {
        StringBuilder sb = new StringBuilder("=== DEV SOURCES (implementation) ===\n\n");
        devFiles.forEach(f -> sb.append("--- ").append(f.getPath()).append(" ---\n")
                .append(f.getContent()).append("\n\n"));
        return sb.toString();
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BatchResult {
        private List<ClassCoverageReport> classes;
    }
}
