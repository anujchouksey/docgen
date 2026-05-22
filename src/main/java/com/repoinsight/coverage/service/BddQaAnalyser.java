package com.repoinsight.coverage.service;

import com.repoinsight.github.model.GitHubFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Locale;

/**
 * Phase-1 BDD QA repo analyser.
 *
 * Parses ALL Cucumber artefacts before any comparison starts:
 *   • .feature files  → FeatureScenario objects (name, tags, Given/When/Then steps)
 *   • step-def .java  → StepDefinition objects (cucumber expression, method body, direct calls)
 *
 * The resulting BddQaIndex is consumed by StaticCoverageAnalyser (offline) and
 * CoverageComparisonAgent (AI) to understand what the QA suite actually covers
 * before comparing against Spring Boot dev classes.
 */
@Component
@Slf4j
public class BddQaAnalyser {

    // Feature file patterns
    private static final Pattern FEATURE_TITLE   = Pattern.compile("^\\s*Feature:\\s*(.+)", Pattern.MULTILINE);
    private static final Pattern STEP_ANNOT      = Pattern.compile(
            "@(?:Given|When|Then|And|But)\\s*\\(\\s*[\"'](.*?)[\"']\\s*\\)", Pattern.DOTALL);
    private static final Pattern METHOD_CALL_PAT = Pattern.compile("\\.(\\w+)\\s*\\(");

    // ── Public entry point ─────────────────────────────────────────────────

    public BddQaIndex buildIndex(List<GitHubFile> qaFiles) {
        List<FeatureScenario> scenarios = new ArrayList<>();
        List<StepDefinition>  stepDefs  = new ArrayList<>();

        for (GitHubFile file : qaFiles) {
            if (file.getPath().endsWith(".feature")) {
                List<FeatureScenario> parsed = parseFeature(file);
                scenarios.addAll(parsed);
                log.debug("Feature '{}': {} scenarios", file.getPath(), parsed.size());
            } else if (file.getPath().endsWith(".java")) {
                List<StepDefinition> parsed = parseStepDefs(file);
                if (!parsed.isEmpty()) {
                    stepDefs.addAll(parsed);
                    log.debug("Step-def '{}': {} patterns", file.getPath(), parsed.size());
                }
            }
        }

        BddQaIndex index = new BddQaIndex(scenarios, stepDefs);
        log.info("BDD QA index built: {} features | {} scenarios (happy={} edge={}) | {} step defs | tags: {}",
                index.featureFiles().size(), scenarios.size(),
                index.happyPathCount(), index.edgeCaseCount(),
                stepDefs.size(), index.tagsSummary());
        return index;
    }

    // ── Feature file parser ────────────────────────────────────────────────

    private List<FeatureScenario> parseFeature(GitHubFile file) {
        String content = file.getContent();
        List<FeatureScenario> results = new ArrayList<>();

        // Feature title
        String featureName = "";
        Matcher fm = FEATURE_TITLE.matcher(content);
        if (fm.find()) featureName = fm.group(1).trim();

        String[] lines = content.split("\\r?\\n");
        List<String> pendingTags  = new ArrayList<>();
        String       scenarioName = null;
        List<String> steps        = new ArrayList<>();
        List<String> currentTags  = new ArrayList<>();

        for (String raw : lines) {
            String t = raw.trim();

            if (t.startsWith("@") && !t.startsWith("@SpringBoot") && !t.startsWith("@Cucumber")) {
                // Collect all tags on the line
                Arrays.stream(t.split("\\s+"))
                      .filter(tok -> tok.startsWith("@"))
                      .forEach(pendingTags::add);

            } else if (t.matches("(?i)Scenario(?:\\s+Outline)?:\\s*.*")) {
                if (scenarioName != null) {
                    results.add(new FeatureScenario(featureName, scenarioName,
                            List.copyOf(currentTags), List.copyOf(steps), file.getPath()));
                }
                scenarioName = t.replaceFirst("(?i)Scenario(?:\\s+Outline)?:\\s*", "").trim();
                currentTags  = List.copyOf(pendingTags);
                pendingTags.clear();
                steps = new ArrayList<>();

            } else if (t.matches("(?i)(Given|When|Then|And|But)\\s+.*")) {
                steps.add(t);

            } else if (t.matches("(?i)Background:\\s*.*")) {
                // Background steps collected under a virtual scenario
                if (scenarioName != null) {
                    results.add(new FeatureScenario(featureName, scenarioName,
                            List.copyOf(currentTags), List.copyOf(steps), file.getPath()));
                }
                scenarioName = "__background__";
                currentTags  = List.of();
                pendingTags.clear();
                steps = new ArrayList<>();
            }
        }
        if (scenarioName != null && !scenarioName.equals("__background__")) {
            results.add(new FeatureScenario(featureName, scenarioName,
                    List.copyOf(currentTags), List.copyOf(steps), file.getPath()));
        }
        return results;
    }

    // ── Step definition parser ─────────────────────────────────────────────

    private List<StepDefinition> parseStepDefs(GitHubFile file) {
        String content = file.getContent();
        if (!content.contains("@Given") && !content.contains("@When")
                && !content.contains("@Then") && !content.contains("@And")) {
            return List.of();
        }

        List<StepDefinition> defs = new ArrayList<>();
        Matcher m = STEP_ANNOT.matcher(content);
        while (m.find()) {
            String pattern = m.group(1);
            // Grab ~800 chars after annotation — covers the method signature + body
            int    end  = m.end();
            String body = content.substring(end, Math.min(end + 800, content.length()));

            // Extract direct method calls from body (.methodName(...)
            List<String> calls = new ArrayList<>();
            Matcher cm = METHOD_CALL_PAT.matcher(body);
            while (cm.find()) calls.add(cm.group(1));

            defs.add(new StepDefinition(pattern, body, List.copyOf(calls), file.getPath()));
        }
        return defs;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Model types
    // ══════════════════════════════════════════════════════════════════════

    /** One parsed Cucumber scenario (or scenario outline). */
    public record FeatureScenario(
            String       featureName,
            String       scenarioName,
            List<String> tags,
            List<String> steps,
            String       filePath) {

        public boolean hasTag(String tag) {
            return tags.stream().anyMatch(t -> t.equalsIgnoreCase(tag));
        }

        public boolean hasAnyTag(String... candidates) {
            return Arrays.stream(candidates).anyMatch(this::hasTag);
        }

        /** All text concatenated (feature + scenario + steps), lower-cased. */
        public String allText() {
            return (featureName + " " + scenarioName + " " + String.join(" ", steps)).toLowerCase();
        }

        /**
         * True if this scenario is an edge/failure/negative test.
         * Detected via tags OR scenario name keywords.
         */
        public boolean isEdgeCase() {
            if (hasAnyTag("@edge-case", "@negative", "@error", "@failure",
                          "@sad-path", "@exception", "@unhappy")) return true;
            String name = scenarioName.toLowerCase();
            return name.matches(".*(fail|error|invalid|not.found|unauthori[sz]|forbidden|"
                    + "timeout|unavailable|duplicate|constraint|rollback|"
                    + "\\b401\\b|\\b403\\b|\\b404\\b|\\b409\\b|\\b500\\b|\\b503\\b).*");
        }

        public boolean isHappyPath() { return !isEdgeCase(); }
    }

    /** One step-definition method (a @Given / @When / @Then annotated method). */
    public record StepDefinition(
            String       pattern,
            String       body,
            List<String> methodCalls,
            String       filePath) {

        /** True if this step def directly invokes or references the given method name. */
        public boolean mentionsMethod(String methodName) {
            String lower = methodName.toLowerCase();
            String bodyL = body.toLowerCase();
            return pattern.toLowerCase().contains(lower)
                    || bodyL.contains("." + lower + "(")
                    || bodyL.contains(" " + lower + "(")
                    || methodCalls.stream().anyMatch(c -> c.equalsIgnoreCase(methodName));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // BddQaIndex
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Fully analysed BDD QA corpus, ready for comparison against a Spring Boot dev repo.
     */
    public record BddQaIndex(
            List<FeatureScenario> scenarios,
            List<StepDefinition>  stepDefs) {

        public static final BddQaIndex EMPTY = new BddQaIndex(List.of(), List.of());

        // ── Scenario queries ───────────────────────────────────────────────

        /** All scenarios whose combined text contains ALL keywords (case-insensitive). */
        public List<FeatureScenario> scenariosMatching(String... keywords) {
            return scenarios.stream()
                    .filter(s -> {
                        String all = s.allText();
                        return Arrays.stream(keywords)
                                     .filter(k -> k.length() >= 3)
                                     .allMatch(k -> all.contains(k.toLowerCase()));
                    })
                    .toList();
        }

        /** Files (.feature) that contain at least one scenario matching the keywords. */
        public List<String> featureFilesMatching(String... keywords) {
            return scenariosMatching(keywords).stream()
                    .map(FeatureScenario::filePath).distinct().toList();
        }

        // ── Step def queries ───────────────────────────────────────────────

        /** True if any step def annotation or body mentions the keyword. */
        public boolean stepDefMentions(String keyword) {
            String kl = keyword.toLowerCase();
            return stepDefs.stream().anyMatch(sd ->
                    sd.pattern().toLowerCase().contains(kl)
                    || sd.body().toLowerCase().contains(kl));
        }

        /** True if any step def directly calls this method name. */
        public boolean stepDefCalls(String methodName) {
            return stepDefs.stream().anyMatch(sd -> sd.mentionsMethod(methodName));
        }

        /** Files (.java) that contain step defs mentioning the keyword. */
        public List<String> stepDefFilesMatching(String keyword) {
            String kl = keyword.toLowerCase();
            return stepDefs.stream()
                    .filter(sd -> sd.pattern().toLowerCase().contains(kl)
                              || sd.body().toLowerCase().contains(kl))
                    .map(StepDefinition::filePath)
                    .distinct().toList();
        }

        // ── Statistics ─────────────────────────────────────────────────────

        public long happyPathCount()  { return scenarios.stream().filter(FeatureScenario::isHappyPath).count(); }
        public long edgeCaseCount()   { return scenarios.stream().filter(FeatureScenario::isEdgeCase).count(); }

        public List<String> featureFiles() {
            return scenarios.stream().map(FeatureScenario::filePath).distinct().toList();
        }
        public List<String> stepDefFiles() {
            return stepDefs.stream().map(StepDefinition::filePath).distinct().toList();
        }
        public List<String> featureNames() {
            return scenarios.stream().map(FeatureScenario::featureName).distinct().toList();
        }

        /** Top-10 tags with frequency counts, e.g. "@smoke×12, @regression×8". */
        public String tagsSummary() {
            Map<String, Long> freq = new LinkedHashMap<>();
            scenarios.forEach(s -> s.tags().forEach(t -> freq.merge(t, 1L, Long::sum)));
            return freq.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(10)
                    .map(e -> e.getKey() + "×" + e.getValue())
                    .collect(Collectors.joining(", "));
        }

        /** All unique tags used across all scenarios. */
        public Set<String> allTags() {
            Set<String> tags = new LinkedHashSet<>();
            scenarios.forEach(s -> tags.addAll(s.tags()));
            return tags;
        }

        public boolean isEmpty() { return scenarios.isEmpty() && stepDefs.isEmpty(); }

        // ── Domain-level coverage queries (E2E / functional BDD) ───────────

        /**
         * Returns true if ANY BDD scenario or step-def text mentions this
         * business domain word.
         *
         * <p>Used for E2E / functional BDD repos where step text describes
         * business behaviour ("When I submit an order") rather than Java method
         * names ("createOrder").  If the domain word "order" appears in at least
         * one scenario or step def, the domain is considered covered.</p>
         */
        public boolean coversDomain(String domain) {
            if (domain == null || domain.isBlank()) return false;
            String dl = domain.toLowerCase(Locale.ROOT);
            return scenarios.stream().anyMatch(s -> s.allText().contains(dl))
                    || stepDefs.stream().anyMatch(sd ->
                            sd.pattern().toLowerCase(Locale.ROOT).contains(dl)
                            || sd.body().toLowerCase(Locale.ROOT).contains(dl));
        }

        /**
         * Returns true if at least one BDD scenario that mentions the given
         * domain is also classified as an edge / failure case.
         */
        public boolean hasEdgeCasesForDomain(String domain) {
            if (domain == null || domain.isBlank()) return false;
            String dl = domain.toLowerCase(Locale.ROOT);
            return scenarios.stream()
                    .filter(s -> s.allText().contains(dl))
                    .anyMatch(FeatureScenario::isEdgeCase);
        }

        /**
         * Returns all scenarios that mention the given domain word.
         * Used to count domain-level BDD coverage depth.
         */
        public List<FeatureScenario> scenariosForDomain(String domain) {
            if (domain == null || domain.isBlank()) return List.of();
            String dl = domain.toLowerCase(Locale.ROOT);
            return scenarios.stream().filter(s -> s.allText().contains(dl)).toList();
        }

        /**
         * Produces a concise human-readable summary for use in LLM prompts.
         * Passed as stable QA pre-analysis context before dev class batches.
         */
        public String toLlmSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== QA BDD SUITE ANALYSIS ===\n\n");
            sb.append("Total scenarios : ").append(scenarios.size())
              .append(" (happy-path=").append(happyPathCount())
              .append(", edge/failure=").append(edgeCaseCount()).append(")\n");
            sb.append("Feature files   : ").append(featureFiles().size()).append("\n");
            sb.append("Step-def files  : ").append(stepDefFiles().size()).append("\n");
            sb.append("Tags in use     : ").append(tagsSummary()).append("\n\n");

            sb.append("Features:\n");
            featureNames().forEach(fn -> {
                long count = scenarios.stream()
                        .filter(s -> s.featureName().equals(fn)).count();
                long edge = scenarios.stream()
                        .filter(s -> s.featureName().equals(fn) && s.isEdgeCase()).count();
                sb.append("  • ").append(fn)
                  .append(" — ").append(count).append(" scenarios")
                  .append(" (").append(edge).append(" edge/failure)\n");
            });

            sb.append("\nStep definition patterns (first 30):\n");
            stepDefs.stream().limit(30).forEach(sd ->
                    sb.append("  → ").append(sd.pattern()).append("\n"));

            sb.append("\nAll tags: ").append(allTags()).append("\n");
            return sb.toString();
        }
    }
}
