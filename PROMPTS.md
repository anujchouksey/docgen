# RepoDocGen — Fallback Prompts

Use these prompts directly with Claude, ChatGPT, or GitHub Copilot Chat when the app is unavailable.
Each prompt is self-contained. Paste your source files at the bottom where indicated.

---

## PROMPT 1 — Full Documentation + Gherkin Suite

**Use when:** You want business logic docs, integration map, and a complete Gherkin test suite for a dev repo.

**How to use:**
1. Copy the prompt below
2. Paste your Java source files at the bottom (replace the placeholder)
3. Send to any LLM

---

```
You are a senior software architect + QA engineer performing a full codebase analysis.
Work through the Java source files below in three sequential steps.

════════════════════════════════════════════
STEP 1 — BUSINESS LOGIC DOCUMENTATION
════════════════════════════════════════════

Identify every distinct business flow in the code.

A business flow is a named, user-visible operation with a clear trigger, sequential steps,
business rules, and observable side effects.

For each flow produce a section in this format:

## <FlowName>
**Trigger:** <HTTP method + path | Kafka topic | @Scheduled cron | event>
**Description:** 2–4 sentences on what this does and why it exists.

**Steps:**
1. ...
2. ...

**Business Rules / Invariants:**
- ...

**Side Effects:**
- Events published, emails sent, cache invalidated, external writes, etc.

════════════════════════════════════════════
STEP 2 — INTEGRATION TOPOLOGY
════════════════════════════════════════════

Scan for every place the application touches external infrastructure.

Detection patterns:
- DB: @Repository, JdbcTemplate, EntityManager, extends JpaRepository, @Transactional writes
- Kafka producer: KafkaTemplate.send(), ProducerRecord
- Kafka consumer: @KafkaListener, ConsumerRecord
- S3: AmazonS3, S3Client, putObject/getObject
- Cache: @Cacheable, @CachePut, @CacheEvict, RedisTemplate, CacheManager
- HTTP outbound: RestTemplate, WebClient, @FeignClient, HttpClient
- Third-party: Stripe, Twilio, SendGrid, Firebase, etc.

For each integration produce one table row:

| Category | Name | Class | Method | Detection Pattern | Direction |
|----------|------|-------|--------|-------------------|-----------|

Categories: DB | KAFKA | S3 | CACHE | HTTP | THIRD_PARTY
Direction: READ | WRITE | BOTH | PRODUCE | CONSUME

════════════════════════════════════════════
STEP 3 — GHERKIN TEST SUITE
════════════════════════════════════════════

For EACH business flow identified in Step 1, generate a complete Gherkin feature file.

Rules:
1. Feature title = flow name
2. Use Background for shared Given steps
3. Use realistic data (no "foo", "bar", "test123")
4. Use Scenario Outline + Examples for parameterised edge cases
5. Tags: @happy-path @edge-case @db @kafka @s3 @cache @http @third-party @auth @concurrency

Cover ALL of these scenario categories for every flow:
a) Happy path — valid inputs, all integrations succeed
b) Boundary / edge inputs — empty collections, zero amounts, max-length strings, null optionals, duplicate records
c) DB — record not found (404), constraint violation (duplicate key), optimistic lock conflict, rollback on downstream failure
d) Kafka — producer timeout / broker unavailable, poison pill, DLQ after max retries, duplicate message (idempotency)
e) S3 — object not found, upload failure, presigned URL expiry, access denied
f) Cache — cache miss → DB fallback, cache miss → DB also fails, stale read during update, explicit eviction
g) HTTP — 400 Bad Request, 401/403 auth failure, 404, 429 rate limit, 500 error, timeout, connection refused
h) Third-party SDK — charge declined, SMS failure, email bounce, quota exceeded
i) Auth — unauthenticated, wrong role, expired JWT, cross-tenant access attempt
j) Concurrency — concurrent update same resource, idempotency key reuse

Separate each feature file with:
### FEATURE_FILE: <FlowName>.feature ###

════════════════════════════════════════════
SOURCE FILES
════════════════════════════════════════════

[PASTE YOUR JAVA SOURCE FILES HERE — one file after another, each preceded by its filename]

--- OrderService.java ---
<content>

--- OrderController.java ---
<content>

(continue for all relevant files)
```

---

## PROMPT 2 — QA Coverage Comparison Report

**Use when:** You have a dev repo and a QA test repo and want to know what is COVERED, PARTIAL, MISSED, or NOT_NEEDED.

**How to use:**
1. Copy the prompt below
2. Paste dev source files in Section A
3. Paste QA test files (JUnit, Cucumber `.feature`, REST-Assured, etc.) in Section B
4. Send to any LLM

---

```
You are a senior QA architect performing a coverage gap analysis between a dev codebase
and its test suite.

════════════════════════════════════════════
COVERAGE STATUS DEFINITIONS
════════════════════════════════════════════

COVERED   — Every significant public method has at least one test that exercises the happy path
            AND at least one failure / edge-case scenario.

PARTIAL   — One or more of:
            • Some public methods have no test at all
            • Tests exist but only cover the happy path (no failure, boundary, or
              infrastructure-failure scenarios)
            • Integration points (DB, Kafka, HTTP, cache) are untested in failure mode
            • A Gherkin feature exists but is missing edge-case/failure scenarios

MISSED    — No QA file semantically references this class or its behaviour.
            A name match alone ("OrderServiceTest exists") is NOT sufficient —
            the test must actually exercise the class's logic.

NOT_NEEDED — Class has no testable business logic:
            • DTOs / POJOs (only getters/setters/equals/hashCode)
            • @Configuration classes with only @Bean methods
            • Exception subclasses (extends RuntimeException)
            • Generated mappers (MapStruct, Lombok @Builder)
            • Application entry points (main())

════════════════════════════════════════════
YOUR TASK
════════════════════════════════════════════

Analyse the dev source files in SECTION A against the QA test files in SECTION B.

For EACH dev class produce one entry in this format:

---
**Class:** <ClassName>
**File:** <path>
**Layer:** SERVICE | CONTROLLER | REPOSITORY | COMPONENT | CLIENT | CONSUMER | OTHER
**Status:** COVERED | PARTIAL | MISSED | NOT_NEEDED

**Covered methods:** <comma-separated list, or "—">
**Missed methods:** <comma-separated list, or "—">

**Explanation:**
<2–4 sentences. What is and isn't tested. Be specific about which scenarios are missing.>

**Implementation notes:**
<1–2 sentences on what this class does internally — key dependencies, transactions, events.>

**Suggested Gherkin for gaps:**
<If PARTIAL or MISSED, provide a ready-to-use Gherkin scenario block.
 Use realistic data. Include the correct @tags.>
---

After all classes, produce an EXECUTIVE SUMMARY:

## Executive Summary
- Total dev classes analysed: N
- COVERED: N (N%)
- PARTIAL: N (N%)
- MISSED: N (N%)
- NOT_NEEDED: N (excluded from score)
- **Overall coverage score: N%**   ← formula: (covered + partial×0.5) / (total − not_needed) × 100

Top gaps to address: (bullet list of the 3–5 highest-priority missing test areas)

════════════════════════════════════════════
SECTION A — DEV SOURCE FILES
════════════════════════════════════════════

[PASTE YOUR DEV JAVA FILES HERE]

--- src/main/java/com/example/OrderService.java ---
<content>

--- src/main/java/com/example/OrderController.java ---
<content>

(continue for all dev files)

════════════════════════════════════════════
SECTION B — QA TEST FILES
════════════════════════════════════════════

[PASTE YOUR QA FILES HERE — JUnit tests, Cucumber .feature files, REST-Assured specs, etc.]

--- src/test/java/com/example/OrderServiceTest.java ---
<content>

--- src/test/resources/features/order_placement.feature ---
<content>

(continue for all QA files)
```

---

## PROMPT 3 — Single Class Deep-Dive

**Use when:** You want to focus on one specific class and get a full Gherkin scenario set for it.

---

```
You are a senior QA engineer. Generate a complete, production-grade Gherkin feature file
for the Java class below.

Cover every public method as one or more scenarios.
For each method cover:
  1. Happy path with realistic data
  2. Invalid / boundary input (null, empty, out-of-range)
  3. Infrastructure failure (DB down, Kafka unavailable, HTTP timeout, cache miss)
  4. Auth failure if the method is secured
  5. Concurrency / idempotency if the method modifies shared state

Use:
- Background for shared setup
- Scenario Outline + Examples for parameterised cases
- Tags: @happy-path @edge-case @db @kafka @http @auth @concurrency
- Realistic domain data (not "test", "foo", "bar")

Then produce the matching Java Cucumber step definition class with:
- @SpringBootTest @CucumberContextConfiguration @ActiveProfiles("test")
- WireMockServer for any HTTP downstream calls
- @EmbeddedKafka + KafkaTestUtils for Kafka
- JdbcTemplate for DB setup/teardown
- @MockBean for any S3/third-party clients

════════
CLASS TO TEST
════════

[PASTE THE JAVA CLASS HERE]
```

---

## Tips for best results

- **Context window**: If your codebase is large, send files in batches of 10–15 at a time and merge the results.
- **Focus by layer**: For coverage comparison, send only SERVICE + CONTROLLER files if you want to skip infrastructure classes.
- **Iterating**: If a scenario is wrong, paste the specific method body and say "this scenario is incorrect because X — regenerate only this scenario".
- **Step definitions**: Ask the LLM "now generate the step definition class for the feature file above" as a follow-up.
