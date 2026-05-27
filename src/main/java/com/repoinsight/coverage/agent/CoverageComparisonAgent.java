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

            FUNDAMENTAL PRINCIPLE — READ THIS FIRST
            ────────────────────────────────────────
            The QA repo is a REST Assured + Cucumber BDD test suite.
            Step definitions make HTTP calls (.post("/api/orders"), .get("/api/users/{id}")).
            They do NOT import, instantiate, or call any class from the dev repo directly.

            This means:
            • Java METHOD NAMES from the dev repo will NEVER appear in BDD text. That is normal.
            • A single BDD scenario "When I send a POST request to /api/orders" exercises the
              ENTIRE request chain: OrderController → OrderService → OrderRepository → Kafka.
              All classes in that chain are covered by that one scenario.
            • You must NEVER mark a class MISSED simply because its method names don't appear
              in BDD step text. That is the wrong test.

            COMMON REST ASSURED + CUCUMBER PATTERNS TO RECOGNISE
            ──────────────────────────────────────────────────────
            Feature file steps (extract HTTP verb + path):
              "When I send a POST request to \"/api/orders\""     → POST /api/orders
              "When I call GET \"/api/users/{id}\""               → GET /api/users/{id}
              "When I make a DELETE request to \"/api/orders/1\"" → DELETE /api/orders/1
              "Given the client sends PUT to \"/api/orders/1\""   → PUT /api/orders/1

            Step def bodies (REST Assured chains):
              given().when().post("/api/orders")                  → POST /api/orders
              RestAssured.given().get("/api/users/" + id)         → GET /api/users/{id}
              requestSpec.put("/api/orders/" + orderId)          → PUT /api/orders/{id}

            Match these extracted endpoints to Spring Boot controller annotations:
              @PostMapping("/api/orders")   in OrderController → POST /api/orders covered
              @GetMapping("/api/orders/{id}") → GET /api/orders/{id} covered (path param = any)

            THE CORRECT QUESTION TO ASK FOR EACH CLASS
            ────────────────────────────────────────────
            Not: "Does this method name appear in BDD text?"
            Yes: "Does the BDD suite test the BUSINESS CAPABILITY this class implements?"

            HOW TO DETERMINE COVERAGE
            ─────────────────────────
            STEP 1 — Derive the class's business domain:
              Strip the layer suffix from the class name to find the entity / domain word.
              OrderService       → domain = "order"
              PaymentController  → domain = "payment"
              UserRepository     → domain = "user"
              KafkaOrderProducer → domain = "order"

            STEP 2 — Does BDD cover this domain at all?
              Search ALL feature file names, scenario names, and step text for the domain word.
              If NO scenario mentions "order" in any form → MISSED (the entire order domain is untested).
              If YES → proceed to Step 3.

            STEP 3 — Does BDD cover the ACTIONS this class performs?
              For each public method, derive its action verb (first word of camelCase):
                createOrder   → action = "create"
                cancelOrder   → action = "cancel"
                findOrderById → action = "find/get/retrieve"
              Then check: does any BDD scenario for this domain contain a synonym of this action?
              Verb synonym groups (treat all as equivalent):
                • create / add / insert / save / register / post / build / submit / generate
                • get / find / fetch / load / retrieve / list / view / show / search / query
                • update / edit / modify / change / patch / alter / amend / put
                • delete / remove / cancel / archive / destroy / revoke / purge
                • validate / check / verify / assert / inspect / confirm / ensure / test
                • process / handle / execute / run / perform / apply / invoke / trigger
                • send / publish / emit / dispatch / notify / produce / broadcast

            STEP 4 — Transitive / implicit coverage:
              For SERVICE, REPOSITORY, COMPONENT, PRODUCER, CONSUMER classes:
              If the BDD suite has scenarios that cover the domain AND those scenarios
              exercise the corresponding CONTROLLER endpoint, all downstream classes
              (services, repos, producers) in that call chain are IMPLICITLY covered.
              Do NOT require each downstream class to have its own explicit BDD scenario.

            STEP 5 — Edge/failure coverage:
              Does BDD have scenarios for this domain with @edge-case, @negative, @db,
              @kafka, @http, @error tags, OR scenario names containing error keywords
              (404, 400, fail, invalid, not found, timeout, duplicate, unauthorized)?

            COVERAGE STATUS RULES
            ─────────────────────
            COVERED   — Domain is tested in BDD AND all key actions have synonym coverage
                         AND at least one edge/failure scenario exists for this domain.

            PARTIAL   — Domain IS tested in BDD BUT one or more of:
                         • Some action verbs have no matching domain scenario
                         • Only happy-path scenarios exist (@smoke only, no edge cases)
                         • Infrastructure failures (DB, Kafka, HTTP 5xx) are absent
                         • One endpoint/operation within the domain is never triggered

            MISSED    — The ENTIRE DOMAIN has no BDD coverage. No feature file, scenario,
                         or step text references this business entity in any form.
                         IMPORTANT: Do NOT use MISSED just because a method name is absent.
                         MISSED means the business capability is completely untested.

            NOT_NEEDED — Class has no testable business logic:
                         • DTOs / POJOs with only getters/setters/equals/hashCode
                         • @Configuration classes with only @Bean methods
                         • Exception subclasses (extends RuntimeException / Exception)
                         • Generated mappers (MapStruct, Lombok @Builder only)
                         • Application entry points (main())

            WORKED EXAMPLES
            ───────────────
            Example A — correctly COVERED:
              Dev class:  OrderService  (methods: createOrder, cancelOrder, findOrderById)
              BDD has:    "Scenario: Place a new order"     → domain=order, action=create ✓
                          "Scenario: Cancel an existing order" → domain=order, action=cancel ✓
                          "Scenario: Retrieve order details"   → domain=order, action=find ✓
                          "@edge-case Scenario: Order not found → 404"
              Result: COVERED (all actions + edge case)

            Example B — correctly PARTIAL (no edge cases):
              Dev class:  OrderService  (same methods)
              BDD has:    "Scenario: Place a new order" only, @smoke tag only
              Result: PARTIAL (create is covered but cancel/find are not, no edge cases)

            Example C — correctly MISSED:
              Dev class:  ReportingService (methods: generateMonthlyReport, exportToPdf)
              BDD has:    zero scenarios mentioning "report" or "export" in any form
              Result: MISSED

            Example D — correctly NOT MISSED (services covered transitively):
              Dev class:  OrderRepository
              BDD has:    "When I POST to /api/orders" → creates an order in the DB
              Result: PARTIAL or COVERED (OrderRepository is exercised by the order creation flow)
                      Do NOT mark as MISSED because "OrderRepository" is not in BDD text.

            OUTPUT (JSON only — no markdown fences, no extra commentary)
            ──────────────────────────────────────────────────────────────
            {
              "classes": [
                {
                  "devClass": "OrderService",
                  "devFile": "src/main/java/com/example/OrderService.java",
                  "layer": "SERVICE",
                  "status": "PARTIAL",
                  "coveredMethods": ["createOrder", "findOrderById"],
                  "missedMethods": ["cancelOrder", "updateOrderStatus"],
                  "missingScenarios": [
                    "cancel order — no BDD scenario covers the cancel/delete action for the order domain",
                    "update order status — no BDD scenario covers the update action for the order domain",
                    "infrastructure: DB unavailable during order creation → 500",
                    "infrastructure: Kafka producer timeout on OrderCreated event"
                  ],
                  "relevantQaFiles": ["order_management.feature", "OrderSteps.java"],
                  "explanation": "BDD has 3 scenarios for the 'order' domain. createOrder and findOrderById are covered via 'place order' and 'retrieve order' scenarios (action synonym match). cancelOrder and updateOrderStatus have no domain scenario with cancel/update synonyms. No @edge-case or @db tags found for the order domain — only @smoke happy-path coverage.",
                  "implementationNotes": "OrderService.createOrder() calls InventoryClient.reserve(), PaymentGateway.charge(), orderRepository.save(), then publishes OrderCreated to Kafka. Entire operation is @Transactional.",
                  "suggestedGherkin": "  @missed @edge-case\\n  Scenario: Cancel order when not yet shipped\\n    Given order 'ORD-001' is in status PROCESSING\\n    When a cancellation request is sent for 'ORD-001'\\n    Then the response status is 200\\n    And the order status is CANCELLED"
                }
              ]
            }

            FIELD RULES
            ───────────
            • layer        : SERVICE | CONTROLLER | REPOSITORY | COMPONENT | CLIENT | CONSUMER | OTHER
            • coveredMethods / missedMethods : use the actual Java method names from DEV SOURCES
            • missingScenarios : describe WHAT business scenario is missing, not the method name
            • suggestedGherkin : valid Gherkin, 2-space indent, realistic domain data, correct @tags
            • implementationNotes : what the class DOES (dependencies, transactions, events)
            • relevantQaFiles : feature files / step-def files that relate to this class's domain
            • explanation : cite domain word, action synonyms used, feature file names, tags
            • NEVER mark MISSED because a method name is absent from BDD text
            • NEVER mark COVERED if edge/failure scenarios are missing for the domain
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

        // Log extracted HTTP endpoints so they're visible in application logs
        var endpoints = bddIndex.coveredEndpoints();
        log.info("BDD HTTP endpoints extracted: {} — e.g. {}",
                endpoints.size(),
                endpoints.stream().limit(5)
                        .map(ep -> ep.httpMethod() + " " + ep.path())
                        .toList());

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
                    REMINDER — E2E / functional BDD testing philosophy:
                    The QA suite tests business behaviour through HTTP. Method names from the
                    dev repo do NOT appear in BDD text. Coverage is assessed at the
                    DOMAIN + ACTION level, not the method-name level.

                    STEP 1 — Re-read the BDD QA SUITE ANALYSIS section at the top.
                    Identify: which business domains (entities) are covered, what action
                    verbs appear in scenarios for each domain, and which domains have
                    edge/failure scenarios.

                    STEP 2 — For each of the %d Spring Boot classes in DEV SOURCES:
                      a) Derive the domain word (strip Service/Controller/Repository suffix)
                      b) Check if BDD covers that domain at all → if not, MISSED
                      c) For each public method, check if BDD has a domain scenario that
                         contains a synonym of the method's action verb
                      d) Check for edge/failure coverage for that domain
                      e) Assign COVERED / PARTIAL / MISSED / NOT_NEEDED

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
