package com.repoinsight.coverage.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoinsight.coverage.service.BddQaAnalyser;
import com.repoinsight.coverage.service.BddQaAnalyser.BddQaIndex;
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
 * AI agent for BDD-QA vs Spring-Boot-API coverage comparison.
 *
 * Two-phase strategy
 * ──────────────────
 * Phase 1  Build a full BDD QA index (features, scenarios, tags, step defs)
 *          using BddQaAnalyser BEFORE touching any dev class.  This index is
 *          serialised to a concise text summary and prepended to every LLM call
 *          so the model always knows the full test surface up front.
 *
 * Phase 2  Process dev Spring Boot classes in batches of ~20 against the cached
 *          QA summary.  Each batch produces ClassCoverageReport objects.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CoverageComparisonAgent {

    private final LlmClient      llmClient;
    private final ObjectMapper   objectMapper;
    private final BddQaAnalyser  bddQaAnalyser;

    private static final int DEV_BATCH_SIZE = 20;

    // ── System prompt (BDD + Spring Boot aware) ────────────────────────────

    private static final String SYSTEM_PROMPT = """
            You are a senior QA architect performing a BDD coverage gap analysis.

            CONTEXT
            ───────
            • QA repo  = a Cucumber BDD test suite (Gherkin .feature files + Java step definitions).
            • Dev repo = a Spring Boot REST API (controllers, services, repositories, components).

            You will receive, for EACH analysis call:
              A) BDD QA SUITE ANALYSIS — a pre-parsed summary of ALL feature files and step
                 definitions in the QA repo.  Read this FIRST and understand what behaviours
                 are already tested before you look at any dev class.
              B) QA RAW FILES — the actual .feature and step-def sources for detailed matching.
              C) DEV SOURCES — the Spring Boot classes for this batch.

            HOW TO MATCH BDD TESTS TO SPRING BOOT CLASSES
            ───────────────────────────────────────────────
            Controllers  — match via HTTP verb + URL path in step text
                           ("When I POST to /orders" or "the client calls GET /users/{id}")
                           and via the resource name (OrderController → "order" in feature names).
            Services     — match via business operation names in scenario titles and step text,
                           and via step-def method bodies that call the service directly.
            Repositories — match via entity name (UserRepository → "user" in scenarios).
            Components   — match via their function name in step text or step-def bodies.

            Step definition files bridge Gherkin → Java.  Always check whether a step def
            method body directly calls or instantiates the dev class under review.

            Tags to recognise:
              @smoke @regression → broad coverage, likely happy path only
              @edge-case @negative @sad-path @failure → failure / boundary scenarios
              @db @kafka @s3 @cache @http → infrastructure-failure scenarios
              @auth @security → auth scenarios

            COVERAGE STATUS RULES
            ─────────────────────
            COVERED   — Every significant public method is exercised by:
                         (a) at least one happy-path Gherkin scenario AND
                         (b) at least one edge/failure scenario (or a tag indicating so).

            PARTIAL   — One or more of:
                         • Some public methods have no matching Gherkin scenario or step def.
                         • Scenarios exist but only cover happy path (@smoke only, no @edge-case).
                         • Integration failure modes (DB, Kafka, HTTP 5xx, cache miss) are absent.
                         • A feature file exists for the resource but misses scenario categories.

            MISSED    — No Gherkin scenario or step def semantically exercises this class.
                         A naming coincidence alone (UserServiceTest file name) is NOT sufficient.

            NOT_NEEDED — Class has no testable business logic:
                         • DTOs / POJOs with only getters/setters/equals/hashCode
                         • @Configuration classes with only @Bean methods
                         • Exception subclasses (extends RuntimeException / Exception)
                         • Generated mappers (MapStruct, Lombok @Builder only)
                         • Application entry points (main())

            OUTPUT (JSON only — no markdown fences, no extra commentary)
            ──────────────────────────────────────────────────────────────
            {
              "classes": [
                {
                  "devClass": "OrderService",
                  "devFile": "src/main/java/com/example/OrderService.java",
                  "layer": "SERVICE",
                  "status": "PARTIAL",
                  "coveredMethods": ["createOrder", "getOrderById"],
                  "missedMethods": ["cancelOrder", "updateOrderStatus"],
                  "missingScenarios": [
                    "cancel order when shipment already dispatched → 409",
                    "DB constraint violation on duplicate order ID",
                    "Kafka producer timeout on OrderCreated event"
                  ],
                  "relevantQaFiles": ["order_management.feature", "OrderSteps.java"],
                  "explanation": "The feature file 'order_management.feature' covers createOrder and getOrderById via happy-path scenarios (@smoke). cancelOrder and updateOrderStatus have no matching Gherkin scenario. Existing scenarios lack @edge-case, @db, or @kafka tags — no infrastructure-failure scenarios present.",
                  "implementationNotes": "OrderService.createOrder() calls InventoryClient.reserve(), PaymentGateway.charge(), orderRepository.save(), then publishes OrderCreated to Kafka. Entire operation is @Transactional.",
                  "suggestedGherkin": "  @missed @edge-case\\n  Scenario: Cancel order after shipment dispatched\\n    Given order 'ORD-001' has status SHIPPED\\n    When a cancellation request is sent for 'ORD-001'\\n    Then the response status is 409\\n    And the error message contains \\"already shipped\\""
                }
              ]
            }

            FIELD RULES
            ───────────
            • layer        : SERVICE | CONTROLLER | REPOSITORY | COMPONENT | CLIENT | CONSUMER | OTHER
            • suggestedGherkin : valid Gherkin, 2-space indent, realistic domain data, correct @tags
            • implementationNotes : what the class DOES (dependencies, transactions, events) — not how to test
            • If a QA file partially covers a class name it in relevantQaFiles even if status=MISSED
            • Do NOT mark COVERED if edge/failure cases are missing
            • Be specific in explanation — cite feature file names and tags
            """;

    // ── Public API ─────────────────────────────────────────────────────────

    public CoverageReport compare(List<GitHubFile> devFiles, List<GitHubFile> qaFiles,
                                  String devRepoUrl, String qaRepoUrl,
                                  String devBranch, String qaBranch,
                                  String jobId, String layerFocus) {
        long start = System.currentTimeMillis();
        log.info("CoverageComparisonAgent: {} dev files, {} QA files", devFiles.size(), qaFiles.size());

        // ── Phase 1: fully analyse the BDD QA repo ─────────────────────────
        log.info("Phase 1: building BDD QA index…");
        BddQaIndex bddIndex = bddQaAnalyser.buildIndex(qaFiles);
        String bddSummary   = bddIndex.toLlmSummary();
        log.info("BDD index: {} scenarios, {} step defs, tags: {}",
                bddIndex.scenarios().size(), bddIndex.stepDefs().size(), bddIndex.tagsSummary());

        // Build raw QA context (full file content for detailed matching)
        String qaRawContext = buildQaRawContext(qaFiles);

        // ── Phase 2: process dev batches ───────────────────────────────────
        List<GitHubFile> filteredDev = filterByLayer(devFiles, layerFocus);
        List<List<GitHubFile>> batches = partition(filteredDev, DEV_BATCH_SIZE);
        log.info("Phase 2: {} batches of dev classes (layer={})", batches.size(), layerFocus);

        List<ClassCoverageReport> allClasses = new ArrayList<>();

        for (int i = 0; i < batches.size(); i++) {
            log.info("Processing batch {}/{}", i + 1, batches.size());
            List<GitHubFile> batch = batches.get(i);

            String fullContext = bddSummary + "\n\n"
                    + qaRawContext + "\n\n"
                    + buildDevContext(batch);

            String userPrompt = """
                    STEP 1 — Re-read the BDD QA SUITE ANALYSIS section at the top.
                    Note all features, scenario names, tags, and step def patterns.

                    STEP 2 — For each of the %d Spring Boot classes in DEV SOURCES below,
                    determine coverage status by matching Gherkin scenarios and step defs
                    to the class's public methods and REST endpoints.

                    Layer focus for this run: %s
                    Return the JSON object with a "classes" array.
                    """.formatted(batch.size(), layerFocus);

            try {
                BatchResult result = llmClient.runAgentWithJsonOutput(
                        SYSTEM_PROMPT, userPrompt, fullContext, BatchResult.class);
                if (result != null && result.getClasses() != null) {
                    allClasses.addAll(result.getClasses());
                }
            } catch (Exception e) {
                log.error("Batch {}/{} failed: {}", i + 1, batches.size(), e.getMessage());
            }
        }

        // ── Executive summary ──────────────────────────────────────────────
        String execSummary = generateExecutiveSummary(allClasses, bddIndex, devRepoUrl, qaRepoUrl);
        CoverageSummary summary = computeSummary(allClasses);

        return CoverageReport.builder()
                .jobId(jobId)
                .devRepoUrl(devRepoUrl).qaRepoUrl(qaRepoUrl)
                .devBranch(devBranch).qaBranch(qaBranch)
                .generatedAt(Instant.now())
                .summary(summary)
                .classes(allClasses)
                .executiveSummary(execSummary)
                .devFilesAnalysed(devFiles.size())
                .qaFilesAnalysed(qaFiles.size())
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    // ── Context builders ───────────────────────────────────────────────────

    /** Compact raw QA content — full file text for the LLM to match against. */
    private String buildQaRawContext(List<GitHubFile> qaFiles) {
        StringBuilder sb = new StringBuilder("=== QA RAW FILES (features + step definitions) ===\n\n");
        qaFiles.forEach(f -> sb.append("--- ").append(f.getPath()).append(" ---\n")
                .append(f.getContent()).append("\n\n"));
        return sb.toString();
    }

    private String buildDevContext(List<GitHubFile> devFiles) {
        StringBuilder sb = new StringBuilder("=== DEV SOURCES (Spring Boot API) ===\n\n");
        devFiles.forEach(f -> sb.append("--- ").append(f.getPath()).append(" ---\n")
                .append(f.getContent()).append("\n\n"));
        return sb.toString();
    }

    // ── Executive summary ──────────────────────────────────────────────────

    private String generateExecutiveSummary(List<ClassCoverageReport> classes,
                                             BddQaIndex bddIndex,
                                             String devRepoUrl, String qaRepoUrl) {
        CoverageSummary s = computeSummary(classes);
        String stats = """
                Dev (Spring Boot API): %s
                QA (BDD Cucumber):     %s
                Coverage score:        %.1f%%
                Total dev classes:     %d  |  Covered: %d  |  Partial: %d  |  Missed: %d  |  Not needed: %d
                BDD scenarios:         %d total  |  Happy path: %d  |  Edge/failure: %d
                BDD tags in use:       %s
                Top missed classes:    %s
                """.formatted(
                devRepoUrl, qaRepoUrl, s.getCoverageScore(),
                s.getTotalDevClasses(), s.getCovered(), s.getPartial(),
                s.getNotCovered(), s.getNotNeeded(),
                bddIndex.scenarios().size(), bddIndex.happyPathCount(), bddIndex.edgeCaseCount(),
                bddIndex.tagsSummary(),
                classes.stream().filter(c -> c.getStatus() == CoverageStatus.MISSED)
                        .map(ClassCoverageReport::getDevClass).limit(5).toList());

        return llmClient.runAgent(
                "You are a QA manager summarising a BDD coverage gap analysis of a Spring Boot API. " +
                "Write 4–6 sentences of flowing prose. Mention the coverage score, worst-covered layers, " +
                "and whether the BDD suite covers edge cases and failure scenarios adequately. " +
                "Note the BDD tag coverage. No bullet points.",
                "Write an executive summary:\n\n" + stats,
                stats);
    }

    // ── Utilities ──────────────────────────────────────────────────────────

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
                .totalDevClasses(classes.size()).covered(covered).partial(partial)
                .notCovered(missed).notNeeded(notNeeded).coverageScore(score).build();
    }

    private List<GitHubFile> filterByLayer(List<GitHubFile> files, String layerFocus) {
        if ("ALL".equals(layerFocus)) return files;
        String hint = switch (layerFocus) {
            case "SERVICE"    -> "Service";
            case "CONTROLLER" -> "Controller";
            case "REPOSITORY" -> "Repository";
            default -> null;
        };
        return hint == null ? files
                : files.stream().filter(f -> f.getPath().contains(hint)).toList();
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size)
            parts.add(list.subList(i, Math.min(i + size, list.size())));
        return parts;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BatchResult {
        private List<ClassCoverageReport> classes;
    }
}
