package com.repoinsight.coverage.service;

import com.repoinsight.coverage.service.BddQaAnalyser.BddQaIndex;
import com.repoinsight.coverage.service.BddQaAnalyser.FeatureScenario;
import com.repoinsight.coverage.service.BddQaAnalyser.StepDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Deep semantic matching engine for BDD-vs-dev coverage analysis.
 *
 * <p>Maps BDD step text (e.g. {@code "validate the xyz details"}) to dev method names
 * (e.g. {@code validateXayz}) using a multi-signal scoring model:</p>
 *
 * <pre>
 *   Signal                               Max pts
 *   ───────────────────────────────────  ───────
 *   Verb exact match                        40
 *   Verb synonym group match                28
 *   Verb stem/prefix partial match          22
 *   Entity exact match  (per token)         12  (total capped at 40)
 *   Entity edit-distance ≤ 1 / stem match   10
 *   Entity subsequence match                 8
 *   Entity edit-distance ≤ 2                 6
 *   Entity substring containment             4
 *   Direct method name in text bonus        10
 *   ───────────────────────────────────  ───────
 *   MATCH threshold                         50
 * </pre>
 *
 * <p><b>Walk-through of the canonical example:</b><br>
 * Method: {@code validateXayz} — tokens ["validate", "xayz"]<br>
 * Step text: {@code "validate the xyz details"} — tokens ["validate", "xyz", "details"]<br>
 * → Verb "validate" == "validate" → 40 pts<br>
 * → Entity "xayz" vs "xyz": editDistance=1 → 10 pts<br>
 * → Total: <b>50 → MATCH ✓</b></p>
 */
@Component
@Slf4j
public class DeepBddMatcher {

    /** Minimum score for a method → BDD text pair to count as a match. */
    public static final int MATCH_THRESHOLD = 50;

    // ── Scoring constants ──────────────────────────────────────────────────
    private static final int VERB_EXACT_SCORE    = 40;
    private static final int VERB_SYNONYM_SCORE  = 28;
    private static final int VERB_STEM_SCORE     = 22;
    private static final int ENTITY_EXACT_SCORE  = 12;
    private static final int ENTITY_EDIT1_SCORE  = 10;
    private static final int ENTITY_SUBSEQ_SCORE =  8;
    private static final int ENTITY_EDIT2_SCORE  =  6;
    private static final int ENTITY_SUBSTR_SCORE =  4;
    private static final int DIRECT_NAME_BONUS   = 10;
    private static final int ENTITY_SCORE_CAP    = 40;

    // ── Stop words removed during tokenisation ─────────────────────────────
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "been",
            "have", "has", "do", "does", "to", "of", "in", "on", "at",
            "for", "with", "by", "from", "into", "i", "it", "its",
            "and", "or", "not", "no", "that", "this", "as", "so",
            "should", "would", "could", "when", "then", "given", "but"
    );

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns {@code true} if any scenario or step-def in the BDD index
     * semantically matches the dev method name with score ≥ {@link #MATCH_THRESHOLD}.
     */
    public boolean matches(String methodName, BddQaIndex bddIndex) {
        return bestMatch(methodName, bddIndex).isMatch();
    }

    /**
     * Returns the highest-scoring {@link MatchResult} found across all BDD
     * text surfaces (scenarios + step defs).  Useful for explanations.
     */
    public MatchResult bestMatch(String methodName, BddQaIndex bddIndex) {
        MatchResult best = MatchResult.NONE;

        for (FeatureScenario scenario : bddIndex.scenarios()) {
            int s = score(methodName, scenario.allText());
            if (s > best.score()) {
                best = new MatchResult(s,
                        scenario.scenarioName(),
                        "scenario «" + scenario.featureName() + " / " + scenario.scenarioName() + "»");
            }
            if (best.score() >= 100) return best;
        }

        for (StepDefinition sd : bddIndex.stepDefs()) {
            // Score against both the annotation pattern and the method body
            int s = Math.max(
                    score(methodName, sd.pattern()),
                    score(methodName, sd.body()));
            if (s > best.score()) {
                best = new MatchResult(s, sd.pattern(), "step-def pattern «" + sd.pattern() + "»");
            }
            if (best.score() >= 100) return best;
        }

        log.trace("bestMatch({}) → score={} via {}", methodName, best.score(), best.reason());
        return best;
    }

    /**
     * Computes a semantic match score [0–100+] between a Java method name and
     * a block of BDD text (step lines, scenario name, step-def body, etc.).
     *
     * <p>Scores can exceed 100 for highly certain matches to preserve ranking;
     * callers should use {@code >= MATCH_THRESHOLD} as the decision boundary.</p>
     */
    public int score(String methodName, String bddText) {
        if (methodName == null || bddText == null || methodName.isBlank()) return 0;

        String textLower = bddText.toLowerCase(Locale.ROOT);
        String nameLower = methodName.toLowerCase(Locale.ROOT);

        // ── Fast path: direct exact name in text ──────────────────────────
        if (textLower.contains(nameLower)) {
            // Whole-word match (preceded/followed by non-alpha or call syntax)
            if (textLower.contains(" " + nameLower + " ")
                    || textLower.contains(" " + nameLower + "(")
                    || textLower.contains("." + nameLower + "(")
                    || textLower.contains("\n" + nameLower + "(")) {
                return 90 + DIRECT_NAME_BONUS; // near-certain
            }
            // Substring match — strong signal but could be a longer compound word
            // Give direct bonus and continue scoring normally on top
        }
        int directBonus = textLower.contains(nameLower) ? DIRECT_NAME_BONUS : 0;

        List<String> methodTokens = splitMethodTokens(methodName);
        if (methodTokens.isEmpty()) return directBonus;

        List<String> textTokens = tokenizeText(textLower);
        if (textTokens.isEmpty()) return directBonus;

        String methodVerb = methodTokens.get(0);
        List<String> methodEntities = methodTokens.subList(1, methodTokens.size());

        int verbScore   = scoreVerb(methodVerb, textTokens);
        int entityScore = scoreEntities(methodEntities, textTokens, textLower);
        int total       = verbScore + entityScore + directBonus;

        log.trace("score({}) verb={} entity={} direct={} total={}",
                methodName, verbScore, entityScore, directBonus, total);
        return total;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Verb scoring
    // ══════════════════════════════════════════════════════════════════════

    private int scoreVerb(String methodVerb, List<String> textTokens) {
        // 1. Exact
        if (textTokens.contains(methodVerb)) return VERB_EXACT_SCORE;

        // 2. Stem match ("validates" → stem "validate")
        String methodStem = stem(methodVerb);
        for (String tt : textTokens) {
            if (stem(tt).equals(methodStem)) return VERB_EXACT_SCORE - 2;
        }

        // 3. Synonym group
        Set<String> synonyms = verbSynonymGroup(methodVerb);
        for (String tt : textTokens) {
            if (synonyms.contains(tt)) return VERB_SYNONYM_SCORE;
            if (synonyms.contains(stem(tt))) return VERB_SYNONYM_SCORE - 2;
        }

        // 4. Verb/synonym as stem-prefix of a text token (e.g. "creating" matches "create")
        for (String tt : textTokens) {
            String ttStem = stem(tt);
            if (ttStem.startsWith(methodStem) || methodStem.startsWith(ttStem)) return VERB_STEM_SCORE;
            for (String syn : synonyms) {
                String synStem = stem(syn);
                if (ttStem.startsWith(synStem) || synStem.startsWith(ttStem)) return VERB_STEM_SCORE - 4;
            }
        }

        return 0;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Entity scoring
    // ══════════════════════════════════════════════════════════════════════

    private int scoreEntities(List<String> methodEntities, List<String> textTokens, String textLower) {
        if (methodEntities.isEmpty()) return 0;
        int total = 0;
        for (String me : methodEntities) {
            if (me.length() < 2) continue;
            total += bestEntityScore(me, textTokens, textLower);
        }
        return Math.min(total, ENTITY_SCORE_CAP);
    }

    private int bestEntityScore(String methodEntity, List<String> textTokens, String textLower) {
        String meStem = stem(methodEntity);
        int best = 0;

        for (String tt : textTokens) {
            int s = pairScore(methodEntity, meStem, tt, stem(tt));
            if (s > best) best = s;
            if (best >= ENTITY_EXACT_SCORE) break;
        }

        // Raw text substring check as a fallback (catches multi-token runs)
        if (best < ENTITY_SUBSTR_SCORE) {
            if (textLower.contains(methodEntity) || textLower.contains(meStem)) {
                best = ENTITY_SUBSTR_SCORE;
            }
        }
        return best;
    }

    /**
     * Scores a single (methodEntity, textToken) pair using all signals
     * in descending confidence order, returning as soon as the highest
     * applicable score is found.
     */
    private int pairScore(String a, String aStem, String b, String bStem) {
        // Exact
        if (a.equals(b)) return ENTITY_EXACT_SCORE;
        // Stem exact
        if (aStem.equals(bStem) && aStem.length() >= 3) return ENTITY_EXACT_SCORE;

        // Prefix / majority overlap
        if (prefixOverlap(a, b)) return ENTITY_EDIT1_SCORE;
        if (prefixOverlap(aStem, bStem)) return ENTITY_EDIT1_SCORE;

        // Edit distance ≤ 1
        if (a.length() >= 3 && b.length() >= 3) {
            int ed = editDistance(a, b);
            if (ed <= 1) return ENTITY_EDIT1_SCORE;

            // Subsequence (both at least 3 chars)
            if (isSubsequence(a, b) || isSubsequence(b, a)) return ENTITY_SUBSEQ_SCORE;

            if (ed == 2) return ENTITY_EDIT2_SCORE;
        }

        // Substring containment (both at least 3 chars)
        if (a.length() >= 3 && b.length() >= 3) {
            if (b.contains(a) || a.contains(b)) return ENTITY_SUBSTR_SCORE;
        }

        return 0;
    }

    /**
     * True when the shorter string is a prefix of the longer AND at least
     * half its length — prevents single-char prefix matches.
     */
    private static boolean prefixOverlap(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        int shorter = Math.min(a.length(), b.length());
        int longer  = Math.max(a.length(), b.length());
        if (shorter * 2 < longer) return false; // shorter is < 50% of longer
        return a.startsWith(b) || b.startsWith(a);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Tokenisation helpers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Splits a camelCase / PascalCase / snake_case method name into lowercase tokens.
     * <pre>
     *   validateXayz   → ["validate", "xayz"]
     *   getOrderById   → ["get", "order", "by", "id"]
     *   create_payment → ["create", "payment"]
     * </pre>
     */
    public static List<String> splitMethodTokens(String methodName) {
        // Split on uppercase transitions AND underscores
        String[] parts = methodName.split("(?=[A-Z])|_+");
        List<String> tokens = new ArrayList<>(parts.length);
        for (String p : parts) {
            String lower = p.toLowerCase(Locale.ROOT).trim();
            if (!lower.isEmpty()) tokens.add(lower);
        }
        return tokens;
    }

    /**
     * Tokenises BDD text: lowercases, splits on non-alphanumeric characters,
     * removes stop words and single-character tokens.
     * <pre>
     *   "validate the xyz details" → ["validate", "xyz", "details"]
     *   "When I POST to /orders/{id}" → ["when", "post", "orders", "id"]
     *                                   (stop words filtered)
     * </pre>
     */
    public static List<String> tokenizeText(String text) {
        String[] raw = text.toLowerCase(Locale.ROOT).split("[^a-zA-Z0-9]+");
        List<String> tokens = new ArrayList<>(raw.length);
        for (String r : raw) {
            if (r.length() >= 2 && !STOP_WORDS.contains(r)) tokens.add(r);
        }
        return tokens;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Edit distance (Levenshtein)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Classic Wagner–Fischer DP implementation.
     * O(m·n) time, O(min(m,n)) space (two rolling rows).
     */
    public static int editDistance(String a, String b) {
        if (a.equals(b)) return 0;
        int la = a.length(), lb = b.length();
        if (la == 0) return lb;
        if (lb == 0) return la;
        // Always iterate over the longer string; shorter string → smaller arrays
        if (la > lb) { String t = a; a = b; b = t; la = lb; lb = b.length(); }

        int[] prev = new int[la + 1];
        int[] curr = new int[la + 1];
        for (int i = 0; i <= la; i++) prev[i] = i;

        for (int j = 1; j <= lb; j++) {
            curr[0] = j;
            for (int i = 1; i <= la; i++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                curr[i] = Math.min(prev[i] + 1,           // deletion
                          Math.min(curr[i - 1] + 1,       // insertion
                                   prev[i - 1] + cost));  // substitution
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[la];
    }

    // ══════════════════════════════════════════════════════════════════════
    // Subsequence detection
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns {@code true} when every character of {@code sub} appears in
     * {@code full} in the same relative order (gaps allowed).
     *
     * <p>Example: {@code isSubsequence("xyz", "xayz")} → {@code true}
     * because x→x, y→y (skipping 'a'), z→z.</p>
     */
    public static boolean isSubsequence(String sub, String full) {
        if (sub.isEmpty()) return true;
        if (sub.length() > full.length()) return false;
        int si = 0;
        for (int fi = 0; fi < full.length() && si < sub.length(); fi++) {
            if (full.charAt(fi) == sub.charAt(si)) si++;
        }
        return si == sub.length();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Verb synonym groups
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns the synonym group for a verb, enabling cross-vocabulary matching.
     * E.g. {@code "create"} maps to the same group as {@code "add"}, {@code "save"},
     * {@code "register"}, {@code "post"}, etc.
     */
    public static Set<String> verbSynonymGroup(String verb) {
        return switch (verb) {
            case "create", "add", "insert", "place", "submit",
                 "register", "save", "new", "post", "put", "build",
                 "make", "generate", "provision" ->
                Set.of("create", "add", "insert", "place", "submit", "register",
                       "save", "new", "post", "build", "make", "generate", "provision", "store");

            case "get", "find", "fetch", "load", "retrieve", "list",
                 "read", "view", "show", "search", "query", "lookup",
                 "obtain", "collect" ->
                Set.of("get", "find", "fetch", "load", "retrieve", "list",
                       "read", "view", "show", "search", "query", "lookup", "obtain", "collect");

            case "update", "edit", "modify", "change", "patch",
                 "alter", "set", "replace", "amend", "adjust" ->
                Set.of("update", "edit", "modify", "change", "patch",
                       "alter", "set", "replace", "amend", "adjust", "put");

            case "delete", "remove", "cancel", "archive", "destroy",
                 "drop", "erase", "purge", "revoke", "deactivate" ->
                Set.of("delete", "remove", "cancel", "archive", "destroy",
                       "drop", "erase", "purge", "revoke", "deactivate", "dismiss");

            case "send", "publish", "emit", "dispatch", "notify",
                 "produce", "push", "broadcast", "forward", "relay" ->
                Set.of("send", "publish", "emit", "dispatch", "notify",
                       "produce", "push", "broadcast", "forward", "relay");

            case "validate", "check", "verify", "assert", "inspect",
                 "confirm", "ensure", "test", "evaluate" ->
                Set.of("validate", "check", "verify", "assert", "inspect",
                       "confirm", "ensure", "test", "evaluate", "examine", "audit");

            case "process", "handle", "execute", "run", "perform",
                 "apply", "compute", "calculate", "invoke" ->
                Set.of("process", "handle", "execute", "run", "perform",
                       "apply", "compute", "calculate", "invoke", "trigger");

            case "init", "initialize", "setup", "start", "begin",
                 "boot", "launch", "configure", "prepare" ->
                Set.of("init", "initialize", "setup", "start", "begin",
                       "boot", "launch", "configure", "prepare", "initiate");

            case "stop", "shutdown", "close", "terminate", "end",
                 "finish", "halt", "disable" ->
                Set.of("stop", "shutdown", "close", "terminate", "end",
                       "finish", "halt", "disable", "deactivate");

            case "parse", "decode", "unmarshal", "deserialize" ->
                Set.of("parse", "decode", "unmarshal", "deserialize", "read", "load", "extract");

            case "serialize", "encode", "marshal", "convert", "transform", "map" ->
                Set.of("serialize", "encode", "marshal", "convert", "transform", "map", "format");

            case "authenticate", "login", "signin", "auth", "identify" ->
                Set.of("authenticate", "login", "signin", "auth", "identify", "logon");

            case "authorize", "permit", "allow", "grant", "access" ->
                Set.of("authorize", "permit", "allow", "grant", "access", "approve");

            case "upload", "import", "ingest" ->
                Set.of("upload", "import", "ingest", "attach");

            case "download", "export", "extract" ->
                Set.of("download", "export", "extract", "pull");

            case "notify", "alert", "warn" ->
                Set.of("notify", "alert", "warn", "inform");

            case "merge", "combine", "aggregate", "join" ->
                Set.of("merge", "combine", "aggregate", "join", "consolidate");

            case "refresh", "reload", "reset", "invalidate" ->
                Set.of("refresh", "reload", "reset", "invalidate", "clear", "flush");

            default -> Set.of(verb);
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // Minimal English stemmer (suffix stripping only)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Strips the most common English inflectional suffixes so that
     * {@code "validates"}, {@code "validating"}, and {@code "validated"}
     * all reduce to {@code "validat"} and match each other.
     *
     * <p>This is intentionally <em>not</em> a full Porter stemmer — it only
     * strips the suffixes that appear regularly in BDD step text and Java method
     * names, to minimise false positives.</p>
     */
    static String stem(String word) {
        if (word.length() < 4) return word;
        // Ordered from longest to shortest to prevent over-stripping
        if (word.endsWith("ations") && word.length() > 7) return word.substring(0, word.length() - 6);
        if (word.endsWith("ation")  && word.length() > 6) return word.substring(0, word.length() - 5);
        if (word.endsWith("ions")   && word.length() > 6) return word.substring(0, word.length() - 4);
        if (word.endsWith("ion")    && word.length() > 5) return word.substring(0, word.length() - 3);
        if (word.endsWith("ing")    && word.length() > 5) return word.substring(0, word.length() - 3);
        if (word.endsWith("ies")    && word.length() > 4) return word.substring(0, word.length() - 3) + "y";
        if (word.endsWith("ed")     && word.length() > 4) return word.substring(0, word.length() - 2);
        if (word.endsWith("er")     && word.length() > 4) return word.substring(0, word.length() - 2);
        if (word.endsWith("es")     && word.length() > 4) return word.substring(0, word.length() - 2);
        if (word.endsWith("s")      && word.length() > 3) return word.substring(0, word.length() - 1);
        return word;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Result type
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Holds the best-found match score and diagnostic information.
     *
     * @param score       numeric score (0–100+); use {@link #isMatch()} for decision
     * @param matchedText the BDD text fragment that produced the score
     * @param reason      human-readable explanation (for report generation)
     */
    public record MatchResult(int score, String matchedText, String reason) {

        /** Sentinel value representing no match found. */
        public static final MatchResult NONE = new MatchResult(0, "", "no BDD match found");

        /** True when the score meets or exceeds {@link #MATCH_THRESHOLD}. */
        public boolean isMatch() { return score >= MATCH_THRESHOLD; }
    }
}
