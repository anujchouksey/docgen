package com.repoinsight.coverage.service;

import com.repoinsight.coverage.model.*;
import com.repoinsight.coverage.service.BddQaAnalyser.BddQaIndex;
import com.repoinsight.coverage.service.BddQaAnalyser.FeatureScenario;
import com.repoinsight.github.model.GitHubFile;
import com.repoinsight.static_analysis.JavaAstParser;
import com.repoinsight.static_analysis.TemplateGherkinGenerator;
import com.repoinsight.static_analysis.model.ParsedClass;
import com.repoinsight.static_analysis.model.ParsedMethod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Locale;

/**
 * AST-based coverage analysis — zero AI, zero API calls, works offline.
 *
 * Algorithm:
 *  1. Parse dev Java files into ParsedClass ASTs
 *  2. Parse QA files (Java via AST, .feature via regex) into QaFileIndex
 *  3. For each dev class:
 *     a. Classify as NOT_NEEDED if it is a DTO/config/exception
 *     b. Find QA files that reference the class (by name, import, or field type)
 *     c. Check which public methods are mentioned in the matched QA files
 *     d. Assign COVERED / PARTIAL / MISSED
 *     e. Generate template Gherkin for any gaps
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StaticCoverageAnalyser {

    private final JavaAstParser            astParser;
    private final TemplateGherkinGenerator gherkinGenerator;
    private final BddQaAnalyser            bddQaAnalyser;
    private final DeepBddMatcher           deepBddMatcher;

    private static final Set<String> NO_NEED_LAYERS = Set.of("DTO", "ENTITY", "CONFIG", "EXCEPTION");
    private static final Set<String> NO_NEED_ANNOTATIONS = Set.of("Configuration", "ConfigurationProperties", "SpringBootApplication");

    // Compiled once — used in QA index building and Gherkin step annotation scanning
    private static final Pattern CLASS_REF_PATTERN = Pattern.compile(
            "\\b([A-Z][a-zA-Z0-9]+(?:Service|Controller|Repository|Handler|Client|Gateway|Manager|Producer|Consumer|Facade|UseCase|Helper|Util|Utils))\\b");
    private static final Pattern STEP_ANNOTATION_PATTERN = Pattern.compile(
            "@(?:When|Given|Then|And|But)\\s*\\(\\s*[\"']([^\"']+)[\"']");

    public CoverageReport analyse(List<GitHubFile> devFiles, List<GitHubFile> qaFiles,
                                  String devRepoUrl, String qaRepoUrl,
                                  String devBranch, String qaBranch,
                                  String jobId, String layerFocus) {
        long start = System.currentTimeMillis();
        log.info("StaticCoverageAnalyser: {} dev files vs {} QA files", devFiles.size(), qaFiles.size());

        // Phase 1 — fully analyse BDD QA repo first
        log.info("Phase 1: building BDD QA index…");
        BddQaIndex bddIndex = bddQaAnalyser.buildIndex(qaFiles);
        log.info("BDD index: {} scenarios (happy={}, edge={}), {} step defs, tags: {}",
                bddIndex.scenarios().size(), bddIndex.happyPathCount(), bddIndex.edgeCaseCount(),
                bddIndex.stepDefs().size(), bddIndex.tagsSummary());

        // Phase 2 — parse dev files, build class-level QA file index
        log.info("Phase 2: parsing dev classes and matching against BDD index…");
        List<ParsedClass> devClasses = devFiles.stream()
                .map(astParser::parse)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(c -> matchesLayerFocus(c, layerFocus))
                .toList();

        // Build combined QA index (Java AST refs + BDD entity matching)
        QaIndex qaIndex = buildQaIndex(qaFiles, bddIndex);

        // Analyse each dev class
        List<ClassCoverageReport> reports = devClasses.stream()
                .map(cls -> analyseClass(cls, qaIndex, bddIndex))
                .toList();

        CoverageSummary summary = computeSummary(reports);
        String execSummary = buildExecutiveSummary(summary, devRepoUrl, qaRepoUrl);

        return CoverageReport.builder()
                .jobId(jobId)
                .devRepoUrl(devRepoUrl).qaRepoUrl(qaRepoUrl)
                .devBranch(devBranch).qaBranch(qaBranch)
                .generatedAt(Instant.now())
                .summary(summary)
                .classes(reports)
                .executiveSummary(execSummary)
                .devFilesAnalysed(devFiles.size())
                .qaFilesAnalysed(qaFiles.size())
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    // ── Per-class analysis ─────────────────────────────────────────────────

    /**
     * Determines BDD coverage for a single dev class.
     *
     * <h3>Core design principle — functional / E2E compatibility</h3>
     * <p>A BDD QA suite written against a REST API tests <em>business behaviour</em>,
     * not implementation details.  A single scenario "When I POST to /api/orders"
     * exercises {@code OrderController}, {@code OrderService}, {@code OrderRepository},
     * and any downstream producers in one shot — none of those class or method names
     * ever appear in the Gherkin text.</p>
     *
     * <p>Therefore coverage is assessed in two stages:</p>
     * <ol>
     *   <li><b>Domain level</b> — does ANY BDD scenario mention the business entity
     *       (domain word) that this class is responsible for?  If no → MISSED.
     *       If yes → proceed to stage 2.</li>
     *   <li><b>Action level</b> — for each public method, does ANY domain-level BDD
     *       scenario also contain a synonym of the action verb the method performs?
     *       ({@code createOrder} → action={@code create} → does any "order" scenario
     *       mention create / add / save / submit / post …?)</li>
     * </ol>
     *
     * <p>Method names are <em>never</em> looked up directly in BDD text.</p>
     */
    private ClassCoverageReport analyseClass(ParsedClass cls, QaIndex qaIndex, BddQaIndex bddIndex) {

        // ── NOT_NEEDED fast exit ───────────────────────────────────────────
        if (isNotNeeded(cls)) {
            return ClassCoverageReport.builder()
                    .devClass(cls.getSimpleName()).devFile(cls.getFilePath()).layer(cls.getLayer())
                    .status(CoverageStatus.NOT_NEEDED)
                    .coveredMethods(List.of()).missedMethods(List.of()).missingScenarios(List.of())
                    .relevantQaFiles(List.of())
                    .explanation("No testable business logic: " + cls.getLayer() + " class with structural-only code.")
                    .implementationNotes(buildImplNotes(cls))
                    .suggestedGherkin(null)
                    .build();
        }

        List<ParsedMethod> publicMethods = cls.getMethods().stream()
                .filter(m -> m.isPublic() && !m.isStatic() && !isObjectMethod(m.getName()))
                .toList();

        if (publicMethods.isEmpty()) {
            return notNeeded(cls, "No public instance methods to test.");
        }

        // ── Stage 1: domain-level check ────────────────────────────────────
        // Derive the single business entity word this class owns.
        // OrderService → "order" | PaymentController → "payment" | UserRepository → "user"
        String domain = QaIndex.deriveEntity(cls.getSimpleName());

        boolean domainCoveredInBdd = bddIndex.coversDomain(domain);

        // Also check via Java-AST class-reference matching (step defs that import/call this class)
        List<String> matchedQaFiles = qaIndex.findMatchingFiles(cls.getSimpleName());
        boolean hasDirectQaRef = !matchedQaFiles.isEmpty();

        if (!domainCoveredInBdd && !hasDirectQaRef) {
            // The entire business domain of this class has NO BDD coverage whatsoever.
            // In E2E testing this is the only meaningful MISSED signal.
            List<String> missedSigs   = publicMethods.stream().map(ParsedMethod::signature).toList();
            List<String> suggestions  = buildMissingScenarios(cls, publicMethods, List.of());
            return ClassCoverageReport.builder()
                    .devClass(cls.getSimpleName()).devFile(cls.getFilePath()).layer(cls.getLayer())
                    .status(CoverageStatus.MISSED)
                    .coveredMethods(List.of()).missedMethods(missedSigs)
                    .missingScenarios(suggestions).relevantQaFiles(List.of())
                    .explanation("The '" + domain + "' business domain has no BDD coverage. "
                            + "No Gherkin scenario or step definition references this business "
                            + "capability — not by entity name, HTTP path, or action verb.")
                    .implementationNotes(buildImplNotes(cls))
                    .suggestedGherkin(buildSuggestedGherkin(cls, publicMethods))
                    .build();
        }

        // ── Stage 2: action-level method coverage ──────────────────────────
        // Domain IS covered by BDD.  For each public method check whether the
        // ACTION it performs (its leading verb) is covered in domain scenarios.
        //
        // createOrder()  → action=create  → any "order" BDD scenario mentions create/add/save/post?
        // cancelOrder()  → action=cancel  → any "order" BDD scenario mentions cancel/delete/remove?
        // findOrderById()→ action=find    → any "order" BDD scenario mentions find/get/fetch/list?
        //
        // Method names are NEVER looked up in BDD text directly.
        Set<String> qaContent = qaIndex.contentOf(matchedQaFiles);
        List<String> covered  = new ArrayList<>();
        List<String> missed   = new ArrayList<>();

        for (ParsedMethod method : publicMethods) {
            boolean actionCovered =
                    // Primary: domain + action verb match in BDD (E2E-compatible)
                    isActionCoveredInBdd(method.getName(), domain, bddIndex)
                    // Fallback A: raw QA file content (step defs that directly call this method)
                    || isMethodMentioned(method.getName(), qaContent)
                    // Fallback B: deep semantic name matching (handles fuzzy variants)
                    || deepBddMatcher.matches(method.getName(), bddIndex);

            if (actionCovered) {
                covered.add(method.signature());
            } else {
                missed.add(method.signature());
                log.debug("Action not found in BDD: method='{}' domain='{}' class='{}'",
                        method.getName(), domain, cls.getSimpleName());
            }
        }

        // ── Edge-case coverage ─────────────────────────────────────────────
        boolean hasEdgeCases = bddIndex.hasEdgeCasesForDomain(domain)
                || hasEdgeCaseScenariosInBdd(bddIndex, matchedQaFiles)
                || hasEdgeCaseScenarios(qaContent);

        // ── Status determination ───────────────────────────────────────────
        CoverageStatus status;
        if (missed.isEmpty() && hasEdgeCases) {
            status = CoverageStatus.COVERED;
        } else {
            // Either some actions are missing, or edge cases are absent.
            // With E2E testing, even if specific action verbs are not found,
            // the class is exercised through the HTTP chain — so PARTIAL, not MISSED.
            status = CoverageStatus.PARTIAL;
        }

        // ── Missing scenario suggestions ───────────────────────────────────
        List<ParsedMethod> missedMethods = missed.stream()
                .map(sig -> publicMethods.stream()
                        .filter(m -> m.signature().equals(sig)).findFirst().orElseThrow())
                .toList();
        List<String> missingScenarios = buildMissingScenarios(cls, missedMethods, matchedQaFiles);
        if (!hasEdgeCases) {
            missingScenarios = new ArrayList<>(missingScenarios);
            missingScenarios.add(0,
                    "No edge/failure scenarios found for the '" + domain + "' domain — "
                    + "add @edge-case, @db, @kafka, @http, or @negative tagged scenarios "
                    + "to achieve full coverage");
        }

        // Merge feature files from BDD domain search with direct QA file matches
        List<String> allRelevantQaFiles = new ArrayList<>(matchedQaFiles);
        bddIndex.featureFilesMatching(domain).stream()
                .filter(f -> !allRelevantQaFiles.contains(f)).forEach(allRelevantQaFiles::add);

        long domainScenarioCount = bddIndex.scenariosForDomain(domain).size();

        return ClassCoverageReport.builder()
                .devClass(cls.getSimpleName()).devFile(cls.getFilePath()).layer(cls.getLayer())
                .status(status)
                .coveredMethods(covered).missedMethods(missed)
                .missingScenarios(missingScenarios).relevantQaFiles(allRelevantQaFiles)
                .explanation(buildExplanation(cls, covered, missed, allRelevantQaFiles,
                        hasEdgeCases, bddIndex, domain, domainScenarioCount))
                .implementationNotes(buildImplNotes(cls))
                .suggestedGherkin(missed.isEmpty() ? null : buildSuggestedGherkin(cls, missedMethods))
                .build();
    }

    // ── Action-level BDD coverage check (E2E-compatible) ──────────────────

    /**
     * Returns true when BDD scenarios that cover the given {@code domain} also
     * exercise the <em>action</em> (leading verb) of the given method.
     *
     * <p>This replaces method-name look-up with <b>domain + action verb</b> matching,
     * which is the only strategy that works for E2E BDD suites that test via HTTP
     * and never reference Java class or method names.</p>
     *
     * <pre>
     *   createOrder()   domain=order  → any "order" scenario mentions create/add/save/post/submit?
     *   cancelOrder()   domain=order  → any "order" scenario mentions cancel/delete/remove?
     *   findOrderById() domain=order  → any "order" scenario mentions find/get/fetch/list/retrieve?
     * </pre>
     */
    private boolean isActionCoveredInBdd(String methodName, String domain, BddQaIndex bddIndex) {
        List<String> tokens = DeepBddMatcher.splitMethodTokens(methodName);
        if (tokens.isEmpty()) return false;

        String actionVerb = tokens.get(0);                           // e.g. "create"
        Set<String> synonyms = DeepBddMatcher.verbSynonymGroup(actionVerb); // e.g. {create,add,save,post…}
        String domainLower = domain.toLowerCase(Locale.ROOT);

        // Check scenarios that mention this domain
        boolean coveredByScenario = bddIndex.scenarios().stream()
                .filter(s -> domainLower.isBlank() || s.allText().contains(domainLower))
                .anyMatch(s -> {
                    String text = s.allText();
                    return synonyms.stream().anyMatch(text::contains);
                });
        if (coveredByScenario) return true;

        // Check step defs whose annotation or body mentions this domain
        return bddIndex.stepDefs().stream()
                .filter(sd -> domainLower.isBlank()
                        || sd.pattern().toLowerCase(Locale.ROOT).contains(domainLower)
                        || sd.body().toLowerCase(Locale.ROOT).contains(domainLower))
                .anyMatch(sd -> {
                    String combined = (sd.pattern() + " " + sd.body()).toLowerCase(Locale.ROOT);
                    return synonyms.stream().anyMatch(combined::contains);
                });
    }

    // ── QA Index ──────────────────────────────────────────────────────────

    private QaIndex buildQaIndex(List<GitHubFile> qaFiles, BddQaIndex bddIndex) {
        Map<String, String>      pathToContent = new HashMap<>();
        Map<String, Set<String>> classToFiles  = new HashMap<>();

        for (GitHubFile f : qaFiles) {
            pathToContent.put(f.getPath(), f.getContent());

            if (f.getPath().endsWith(".java")) {
                astParser.parse(f).ifPresentOrElse(cls -> {
                    addMapping(classToFiles, cls.getSimpleName(), f.getPath());
                    cls.getFields().forEach(field ->
                            addMapping(classToFiles, extractBaseType(field.getType()), f.getPath()));
                    cls.getMethods().forEach(m -> m.getCalledClasses().forEach(c ->
                            addMapping(classToFiles, c, f.getPath())));
                }, () -> extractClassReferences(f.getContent()).forEach(c ->
                        addMapping(classToFiles, c, f.getPath())));
            } else if (!f.getPath().endsWith(".feature")) {
                // .yml, .properties etc — regex class-name scan
                extractClassReferences(f.getContent()).forEach(c ->
                        addMapping(classToFiles, c, f.getPath()));
            }
            // .feature files: BDD entity matching is handled in QaIndex.findMatchingFiles via bddIndex
        }
        return new QaIndex(pathToContent, classToFiles, bddIndex);
    }

    private void addMapping(Map<String, Set<String>> map, String className, String file) {
        if (className == null || className.isBlank()) return;
        map.computeIfAbsent(className, k -> new HashSet<>()).add(file);
    }

    private Set<String> extractClassReferences(String content) {
        Set<String> refs = new HashSet<>();
        Matcher m = CLASS_REF_PATTERN.matcher(content);
        while (m.find()) refs.add(m.group(1));
        return refs;
    }

    private boolean isMethodMentioned(String methodName, Set<String> qaContent) {
        for (String content : qaContent) {
            if (content.contains(methodName)) return true;
            if (content.contains(toSnake(methodName))) return true;
            if (isGherkinEnglishMentioned(methodName, content)) return true;
            if (isStepDefinitionMentioned(methodName, content)) return true;
        }
        return false;
    }

    /**
     * Understands Gherkin English step text semantically.
     *
     * Strategy — three tiers, any hit returns true:
     *  1. Direct camelCase word presence (all segments must appear)
     *  2. Verb synonym match: "create" covers createOrder, addOrder, placeOrder, saveOrder
     *  3. Entity match: "order" appears in a When/Then step alongside a known verb
     */
    private boolean isGherkinEnglishMentioned(String methodName, String content) {
        String lower = methodName.toLowerCase();
        String contentLower = content.toLowerCase();

        // Tier 1: all camelCase words present in content
        String[] words = lower.split("(?=[A-Z])|(?<=[a-z])(?=[A-Z])");
        if (words.length > 1) {
            boolean allPresent = Arrays.stream(words)
                    .filter(w -> w.length() >= 3)
                    .allMatch(contentLower::contains);
            if (allPresent) return true;
        }

        // Tier 2: verb synonym groups
        String[] segments = splitCamel(methodName);
        if (segments.length == 0) return false;
        String verb = segments[0].toLowerCase();
        String entity = segments.length > 1 ? segments[segments.length - 1].toLowerCase() : "";

        Set<String> verbSynonyms = verbSynonymGroup(verb);
        boolean verbFound = verbSynonyms.stream().anyMatch(contentLower::contains);
        boolean entityFound = entity.isEmpty() || contentLower.contains(entity);

        // Only count if the match occurs on a Gherkin step line (Given/When/Then/And/But)
        if (verbFound && entityFound && hasGherkinStepLine(contentLower, entity, verbSynonyms)) {
            return true;
        }

        // Tier 3: methodName appears as camelCase in a step definition annotation value
        // e.g. @When("I call createOrder") or @When(".*createOrder.*")
        return contentLower.contains(lower);
    }

    /**
     * Checks step definition files: looks for @Given/@When/@Then annotation values
     * that reference the method name (directly or via regex pattern).
     * Also checks if the step def method body calls the dev method.
     */
    private boolean isStepDefinitionMentioned(String methodName, String content) {
        if (!content.contains("@When") && !content.contains("@Given")
                && !content.contains("@Then") && !content.contains("@And")) {
            return false;
        }
        String lower = methodName.toLowerCase();
        String snake = toSnake(methodName);
        String contentLower = content.toLowerCase();

        // Annotation value contains the method name or snake version
        Matcher matcher = STEP_ANNOTATION_PATTERN.matcher(content);
        while (matcher.find()) {
            String stepText = matcher.group(1).toLowerCase();
            if (stepText.contains(lower) || stepText.contains(snake)) return true;
            // Semantic: check verb+entity within step text
            String[] segments = splitCamel(methodName);
            if (segments.length > 0) {
                String verb = segments[0].toLowerCase();
                String entity = segments.length > 1 ? segments[segments.length - 1].toLowerCase() : "";
                boolean verbMatch = verbSynonymGroup(verb).stream().anyMatch(stepText::contains);
                boolean entityMatch = entity.isEmpty() || stepText.contains(entity);
                if (verbMatch && entityMatch) return true;
            }
        }

        // Step def method body directly references the dev method name
        return contentLower.contains("." + lower + "(") || contentLower.contains(" " + lower + "(");
    }

    private boolean hasGherkinStepLine(String contentLower, String entity, Set<String> verbSynonyms) {
        for (String line : contentLower.split("\\n")) {
            String trimmed = line.trim();
            boolean isStep = trimmed.startsWith("when ") || trimmed.startsWith("then ")
                    || trimmed.startsWith("given ") || trimmed.startsWith("and ")
                    || trimmed.startsWith("but ");
            if (!isStep) continue;
            boolean hasVerb = verbSynonyms.stream().anyMatch(trimmed::contains);
            boolean hasEntity = entity.isEmpty() || trimmed.contains(entity);
            if (hasVerb && hasEntity) return true;
        }
        return false;
    }

    /** Delegates to the canonical synonym table in {@link DeepBddMatcher}. */
    private static Set<String> verbSynonymGroup(String verb) {
        return DeepBddMatcher.verbSynonymGroup(verb);
    }

    private static String[] splitCamel(String name) {
        return name.split("(?=[A-Z])|(?<=[a-z])(?=[A-Z])");
    }

    private boolean hasEdgeCaseScenarios(Set<String> qaContent) {
        List<String> failureIndicators = List.of(
                "404", "400", "409", "500", "503", "exception", "error", "fail",
                "invalid", "null", "empty", "not found", "expired", "timeout",
                "@edge", "@db", "@kafka", "@cache", "@http", "edge-case"
        );
        for (String content : qaContent) {
            String lower = content.toLowerCase();
            if (failureIndicators.stream().anyMatch(lower::contains)) return true;
        }
        return false;
    }

    // ── Explanation & suggestions ─────────────────────────────────────────

    private String buildExplanation(ParsedClass cls, List<String> covered, List<String> missed,
                                    List<String> qaFiles, boolean hasEdgeCases, BddQaIndex bddIndex,
                                    String domain, long domainScenarioCount) {
        StringBuilder sb = new StringBuilder();

        // Domain-level summary
        if (!domain.isBlank()) {
            sb.append("BDD suite has ").append(domainScenarioCount)
              .append(" scenario(s) covering the '").append(domain).append("' business domain. ");
        }
        if (!qaFiles.isEmpty()) {
            sb.append("Relevant QA files: ").append(String.join(", ", qaFiles)).append(". ");
        }

        // Action-level coverage summary
        int total = covered.size() + missed.size();
        sb.append(covered.size()).append(" of ").append(total)
          .append(" public method actions are exercised in BDD via domain+action matching");
        if (covered.size() == total) {
            sb.append(" (all actions covered)");
        } else if (!missed.isEmpty()) {
            sb.append("; no BDD scenario covers the '").append(domain).append("' domain with actions: ")
              .append(missed.stream()
                      .map(sig -> sig.substring(0, sig.contains("(") ? sig.indexOf("(") : sig.length()))
                      .collect(Collectors.joining(", ")));
        }
        sb.append(". ");

        // Edge case summary
        if (hasEdgeCases) {
            sb.append("Edge/failure scenarios ARE present for the '").append(domain)
              .append("' domain (tags: ").append(bddIndex.tagsSummary()).append("). ");
        } else {
            sb.append("No edge/failure scenarios found for the '").append(domain)
              .append("' domain — only happy-path coverage detected. ");
        }

        return sb.toString();
    }

    /** BDD-aware edge case check: look at tags on scenarios that match the QA files. */
    private boolean hasEdgeCaseScenariosInBdd(BddQaIndex bddIndex, List<String> matchedFiles) {
        Set<String> matchedFileSet = new HashSet<>(matchedFiles);
        return bddIndex.scenarios().stream()
                .filter(s -> matchedFileSet.contains(s.filePath()))
                .anyMatch(FeatureScenario::isEdgeCase);
    }

    private String buildImplNotes(ParsedClass cls) {
        StringBuilder sb = new StringBuilder();
        sb.append(cls.getSimpleName()).append(" [").append(cls.getLayer()).append("] in ").append(cls.getFilePath()).append(". ");
        if (!cls.getImplementedInterfaces().isEmpty())
            sb.append("Implements: ").append(String.join(", ", cls.getImplementedInterfaces())).append(". ");
        if (cls.getSuperClass() != null)
            sb.append("Extends: ").append(cls.getSuperClass()).append(". ");

        List<String> injected = cls.getFields().stream()
                .filter(f -> f.getAnnotations().contains("Autowired") || f.getAnnotations().contains("Inject")
                        || cls.getClassAnnotations().contains("RequiredArgsConstructor"))
                .map(f -> f.getType() + " " + f.getName())
                .toList();
        if (!injected.isEmpty())
            sb.append("Depends on: ").append(String.join(", ", injected)).append(". ");

        List<String> pubMethods = cls.getMethods().stream()
                .filter(ParsedMethod::isPublic).filter(m -> !m.isStatic()).filter(m -> !isObjectMethod(m.getName()))
                .map(ParsedMethod::signature).toList();
        if (!pubMethods.isEmpty())
            sb.append("Public API: ").append(String.join(", ", pubMethods)).append(".");

        return sb.toString();
    }

    private List<String> buildMissingScenarios(ParsedClass cls, List<ParsedMethod> missedMethods, List<String> existingQa) {
        List<String> scenarios = new ArrayList<>();
        for (ParsedMethod m : missedMethods) {
            scenarios.add("Happy path: " + m.signature());
            if (m.getName().startsWith("get") || m.getName().startsWith("find") || m.getName().startsWith("load"))
                scenarios.add(m.getName() + ": entity not found → 404");
            if (m.getName().startsWith("create") || m.getName().startsWith("save") || m.getName().startsWith("add"))
                scenarios.add(m.getName() + ": duplicate record → 409 / constraint violation");
            if (m.getName().startsWith("delete") || m.getName().startsWith("cancel"))
                scenarios.add(m.getName() + ": entity in invalid state for deletion");
            if (m.getAnnotations().contains("Transactional"))
                scenarios.add(m.getName() + ": transaction rollback on downstream failure");
        }
        // Layer-level standard missing scenarios
        if ("SERVICE".equals(cls.getLayer()) && existingQa.isEmpty()) {
            scenarios.add("All integration failure modes (DB unavailable, Kafka timeout, HTTP 5xx)");
        }
        if ("CONTROLLER".equals(cls.getLayer())) {
            scenarios.add("Unauthenticated request → 401");
            scenarios.add("Insufficient role → 403");
            scenarios.add("Malformed request body → 400");
        }
        return scenarios.stream().distinct().toList();
    }

    private String buildSuggestedGherkin(ParsedClass cls, List<ParsedMethod> methods) {
        if (methods.isEmpty()) return null;
        ParsedMethod first = methods.get(0);
        String entity = cls.getSimpleName().replace("Service", "").replace("Controller", "").replace("Repository", "").toLowerCase();
        return String.format("""
                @missed
                Scenario: %s %s happy path
                  Given a valid %s request
                  When %s is called on %s
                  Then the operation completes successfully

                @missed @edge-case
                Scenario: %s %s returns 404 when not found
                  Given no %s exists with the given id
                  When %s is called
                  Then the response status is 404
                  And the error message contains "not found"
                """,
                cls.getSimpleName(), first.getName(), entity,
                first.getName(), cls.getSimpleName(),
                cls.getSimpleName(), first.getName(), entity, first.getName());
    }

    // ── Summary ────────────────────────────────────────────────────────────

    private CoverageSummary computeSummary(List<ClassCoverageReport> reports) {
        int covered = 0, partial = 0, missed = 0, notNeeded = 0;
        for (ClassCoverageReport r : reports) {
            switch (r.getStatus()) {
                case COVERED -> covered++;
                case PARTIAL -> partial++;
                case MISSED -> missed++;
                case NOT_NEEDED -> notNeeded++;
            }
        }
        int testable = reports.size() - notNeeded;
        double score = testable == 0 ? 0
                : Math.round((covered + partial * 0.5) / testable * 1000.0) / 10.0;
        return CoverageSummary.builder()
                .totalDevClasses(reports.size()).covered(covered).partial(partial)
                .notCovered(missed).notNeeded(notNeeded).coverageScore(score).build();
    }

    private String buildExecutiveSummary(CoverageSummary s, String devRepo, String qaRepo) {
        String risk = s.getCoverageScore() < 40 ? "high" : s.getCoverageScore() < 70 ? "moderate" : "low";
        return ("Static analysis of %s against %s found a coverage score of %.1f%%. " +
                "%d of %d testable classes are fully covered, %d are partially covered, and %d have no test coverage at all. " +
                "%d classes were classified as structural code requiring no tests. " +
                "Overall test coverage risk is %s. " +
                "Prioritise writing tests for the %d missed classes, focusing on failure modes and edge cases.")
                .formatted(devRepo, qaRepo, s.getCoverageScore(),
                        s.getCovered(), s.getTotalDevClasses() - s.getNotNeeded(),
                        s.getPartial(), s.getNotCovered(), s.getNotNeeded(),
                        risk, s.getNotCovered());
    }

    // ── Utilities ──────────────────────────────────────────────────────────

    private boolean isNotNeeded(ParsedClass cls) {
        if (NO_NEED_LAYERS.contains(cls.getLayer())) return true;
        if (cls.getClassAnnotations().stream().anyMatch(NO_NEED_ANNOTATIONS::contains)) return true;
        if ("EXCEPTION".equals(cls.getLayer())) return true;
        // Only getters/setters/equals/hashCode/toString → not needed
        long businessMethods = cls.getMethods().stream()
                .filter(ParsedMethod::isPublic).filter(m -> !m.isStatic())
                .filter(m -> !isBoilerplateMethod(m.getName()))
                .count();
        return businessMethods == 0;
    }

    private ClassCoverageReport notNeeded(ParsedClass cls, String reason) {
        return ClassCoverageReport.builder()
                .devClass(cls.getSimpleName()).devFile(cls.getFilePath()).layer(cls.getLayer())
                .status(CoverageStatus.NOT_NEEDED)
                .coveredMethods(List.of()).missedMethods(List.of()).missingScenarios(List.of())
                .relevantQaFiles(List.of()).explanation(reason)
                .implementationNotes(buildImplNotes(cls)).suggestedGherkin(null).build();
    }

    private boolean isBoilerplateMethod(String name) {
        return name.startsWith("get") || name.startsWith("set") || name.startsWith("is")
                || name.equals("equals") || name.equals("hashCode") || name.equals("toString")
                || name.equals("canEqual") || name.equals("builder");
    }

    private boolean isObjectMethod(String name) {
        return Set.of("equals", "hashCode", "toString", "clone", "finalize").contains(name);
    }

    private boolean matchesLayerFocus(ParsedClass cls, String layerFocus) {
        if ("ALL".equals(layerFocus)) return true;
        return cls.getLayer().equals(layerFocus);
    }

    private String toSnake(String camel) {
        return camel.replaceAll("([A-Z])", "_$1").toLowerCase().replaceFirst("^_", "");
    }

    private String extractBaseType(String type) {
        return type.replaceAll("<.*>", "").trim();
    }

    // ── QaIndex inner class ────────────────────────────────────────────────

    record QaIndex(Map<String, String> pathToContent,
                   Map<String, Set<String>> classToFiles,
                   BddQaIndex bddIndex) {

        List<String> findMatchingFiles(String className) {
            Set<String> files = new HashSet<>();

            // 1. Direct class-name match (e.g. OrderService → OrderServiceTest.java)
            classToFiles.getOrDefault(className, Set.of()).forEach(files::add);

            // 2. Convention prefix match (OrderService → OrderServiceIT, OrderServiceSpec…)
            classToFiles.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(className))
                    .forEach(e -> files.addAll(e.getValue()));

            // 3. BDD entity match — derive business entity from class name
            //    OrderService → "order", UserController → "user", PaymentRepository → "payment"
            String entity = deriveEntity(className);
            if (!entity.isBlank()) {
                // Feature files whose scenarios mention the entity word
                bddIndex.featureFilesMatching(entity).forEach(files::add);
                // Step def files that mention the entity in annotation or body
                bddIndex.stepDefFilesMatching(entity).forEach(files::add);
            }

            return List.copyOf(files);
        }

        /**
         * Strips layer suffixes to get the core business entity word.
         * OrderService → order | PaymentController → payment | UserRepository → user
         */
        private static String deriveEntity(String className) {
            return className
                    .replaceAll("(?i)(Service|Controller|Repository|Handler|Client|"
                               + "Gateway|Manager|Producer|Consumer|Facade|UseCase|Helper|Util|Utils)$", "")
                    .replaceAll("([A-Z])", " $1")
                    .trim()
                    .toLowerCase();
        }

        Set<String> contentOf(List<String> paths) {
            return paths.stream()
                    .map(pathToContent::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        }
    }
}
