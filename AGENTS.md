# AGENTS.md — RepoDocGen

This file is the authoritative guide for any AI coding agent working in this repository.
Read it fully before making any change.

---

## What this project does

RepoDocGen is a Spring Boot 3.3.5 / Java 21 web service that analyses GitHub Java repositories
and produces two outputs:

1. **Documentation generation** — business logic narrative, method call hierarchy, integration
   topology map, and complete Gherkin feature files with step definitions.

2. **QA coverage comparison** — compares a dev repo against a QA test repo and produces a
   per-class report: COVERED / PARTIAL / MISSED / NOT_NEEDED with explanations and suggested
   Gherkin for every gap.

Both features have a web UI (single-page app, no build step) and a REST API.

---

## Architecture in one diagram

```
HTTP request
    │
    ▼
RepoDocController          CoverageController
    │                           │
    ▼                           ▼
RepoDocumentationService   CoverageComparisonService
 (@Async "agentExecutor")   (@Async "agentExecutor")
    │                           │
    ├─ AI mode (default)        ├─ AI mode (default)
    │   RepoAnalysisOrchestrator│   CoverageComparisonAgent
    │   ├─ BusinessLogicAgent   │       uses LlmClient
    │   ├─ MethodHierarchyAgent │
    │   ├─ IntegrationAgent     ├─ STATIC mode (no LLM)
    │   └─ GherkinAgent         │   StaticCoverageAnalyser
    │                           │
    └─ STATIC mode (no LLM)
        StaticAnalysisOrchestrator
        ├─ JavaAstParser
        ├─ StaticBusinessLogicExtractor
        ├─ StaticIntegrationDetector
        ├─ StaticMethodHierarchyBuilder
        └─ TemplateGherkinGenerator
```

**LLM backend:** `GitHubCopilotClient` implements `LlmClient`. It is the only LLM bean.
There is NO Anthropic/Claude client. Do not add one unless explicitly asked.

---

## Package map

| Package | Purpose |
|---|---|
| `com.repoinsight` | Spring Boot entry point |
| `com.repoinsight.llm` | `LlmClient` interface + `GitHubCopilotClient` (only LLM impl) |
| `com.repoinsight.agent` | AI agents: BusinessLogic, MethodHierarchy, Integration, Gherkin, Orchestrator |
| `com.repoinsight.analyzer.model` | Shared domain models: `AnalysisResult`, `BusinessFlow`, `IntegrationPoint`, `MethodCallNode` |
| `com.repoinsight.github` | `GitHubRepoFetcher` (fetches Java files via GitHub API), `GitHubFile` model |
| `com.repoinsight.generator` | `DocumentationGenerator` — renders Markdown from `AnalysisResult` |
| `com.repoinsight.service` | `RepoDocumentationService`, `AnalysisJob` (JPA entity), `AnalysisJobRepository` |
| `com.repoinsight.controller` | `RepoDocController` (REST API for analysis jobs) |
| `com.repoinsight.coverage.agent` | `CoverageComparisonAgent` — AI-powered dev-vs-QA comparison |
| `com.repoinsight.coverage.controller` | `CoverageController` (REST API for coverage jobs) |
| `com.repoinsight.coverage.model` | `CoverageReport`, `ClassCoverageReport`, `CoverageStatus`, `CoverageSummary` |
| `com.repoinsight.coverage.service` | `CoverageComparisonService`, `CoverageJob`, `QARepoFetcher`, `StaticCoverageAnalyser` |
| `com.repoinsight.static_analysis` | JavaParser-based offline engine and its models |
| `com.repoinsight.config` | `AppConfig` (beans), `SecurityConfig`, `SystemInfoLogger` |

---

## Key files — read these before any change

| File | Why it matters |
|---|---|
| `src/main/java/com/repoinsight/llm/LlmClient.java` | The only LLM interface. Both `runAgent` and `runAgentWithJsonOutput` must stay here. |
| `src/main/java/com/repoinsight/llm/GitHubCopilotClient.java` | Only LLM implementation. Handles token exchange + refresh. No `@ConditionalOnProperty` — always active. |
| `src/main/java/com/repoinsight/service/RepoDocumentationService.java` | Routes jobs: `!"STATIC".equalsIgnoreCase(analysisMode)` → AI path, else static path. |
| `src/main/java/com/repoinsight/coverage/service/CoverageComparisonService.java` | Same routing logic for coverage jobs. |
| `src/main/java/com/repoinsight/static_analysis/StaticCoverageAnalyser.java` | 3-tier Gherkin matching (camelCase words → verb synonyms → step def annotation scan). Do not simplify this logic. |
| `src/main/resources/application.yml` | All config. `analysis.engine` does NOT exist — mode is per-job via `analysisMode` field. |
| `src/main/resources/static/index.html` | SPA served as static resource. No build step. Tailwind CDN + Alpine.js + Chart.js. |

---

## Mode selection — how AI vs STATIC is chosen

Mode is **per job**, not a global config switch. The request body includes `analysisMode: "AI" | "STATIC"`.
Default is `"AI"` (meaning Copilot). `"STATIC"` uses JavaParser with no LLM.

```
// RepoDocumentationService.java
boolean useAi = !"STATIC".equalsIgnoreCase(job.getAnalysisMode());
AnalysisResult result = useAi
    ? aiOrchestrator.orchestrate(...)
    : staticOrchestrator.orchestrate(...);
```

There is no `analysis.engine` property. Do not add one.

---

## LLM client — rules

- `GitHubCopilotClient` exchanges a GitHub PAT (`GITHUB_TOKEN`) for a short-lived Copilot session
  token via `github.copilot.token-exchange-url`. Token refresh happens automatically 60 s before
  expiry using a `ReentrantLock`.
- All calls are blocking (`.block()`) — agents run on the `agentExecutor` thread pool, not the
  reactive event loop.
- On HTTP 401 the cached token is cleared and re-exchanged on the next call.
- Enterprise GHES: set `COPILOT_TOKEN_URL` to
  `https://github.mycompany.com/api/v3/copilot_internal/v2/token`.

**Do not add an Anthropic/Claude client.** `ClaudeAgentClient` and `AnthropicConfig` were
deliberately removed. The `anthropic-java` SDK is not in `pom.xml`.

---

## Static analysis engine

Located in `com.repoinsight.static_analysis`. Works offline — no API key needed.

Pipeline:
```
GitHubFile list
  → JavaAstParser           → List<ParsedClass>
  → StaticBusinessLogicExtractor → List<BusinessFlow>
  → StaticIntegrationDetector    → List<IntegrationPoint>
  → StaticMethodHierarchyBuilder → List<MethodCallNode>
  → TemplateGherkinGenerator     → List<GeneratedGherkin>
```

`TemplateGherkinGenerator.generateWithStepDefs()` returns `GeneratedGherkin` records containing
both the `.feature` content and a compilable Java step definition class. Integration-specific
helpers (JdbcTemplate, @EmbeddedKafka, WireMockServer, @MockBean S3Client) are generated
conditionally based on detected integration categories.

`StaticCoverageAnalyser` uses three-tier matching to avoid false MISSED results:
1. camelCase word segments — `createOrder` → `[create, order]`, both must appear in Gherkin text
2. Verb synonym groups — `create/add/place/submit/save` all match `createOrder`
3. Step definition annotation scanning — `@When/@Given/@Then` values + method body `.methodName(` scan

---

## Testing rules

### Unit tests (Mockito, no Spring context)

- `@ExtendWith(MockitoExtension.class)` + `@Mock` + `@InjectMocks`
- Mock field name **must match** the agent's field name for field-injection fallback:
  - `BusinessLogicAgent` field: `llmClient` → test mock: `@Mock LlmClient llmClient`
  - `GherkinAgent` field: `llmClient` → test mock: `@Mock LlmClient llmClient`
  - `CoverageComparisonAgent` fields: `llmClient`, `objectMapper` → mock both by exact name
- **Never use `thenCallRealMethod()` on an interface mock.** `LlmClient` is an interface.
- **Never stub `runAgentWithJsonOutput` to return `null`.** Agents call `result.getFlows()` immediately — NPE.
- Use `argThat((String x) -> x.contains(...))` for string content assertions. `stringThat` does not exist.

### Fetcher tests (MockWebServer, no Spring context)

When constructing `GitHubRepoFetcher` or `QARepoFetcher` directly (without Spring), `@Value`
fields are unset (0 / null). Always inject them via `ReflectionTestUtils.setField()`:

```java
ReflectionTestUtils.setField(fetcher, "maxFileSizeBytes", 102400L);
ReflectionTestUtils.setField(fetcher, "maxFiles", 500);
// GitHubRepoFetcher also needs:
ReflectionTestUtils.setField(fetcher, "secretPatterns", List.of("(?i)(password|...)..."));
ReflectionTestUtils.setField(fetcher, "excludedPathSegments", List.of("/test/", "/target/", ...));
```

### Web layer tests (`@WebMvcTest`)

- CSRF is disabled in `SecurityConfig`. Tests do not need `.with(csrf())`.
- `@WebMvcTest` loads `SecurityConfig` — API paths (`/api/**`) are `permitAll()`.
- `RepoDocumentationService.createJob` takes **3 args** `(repoUrl, branch, mode)`.
  Stub with `when(service.createJob(anyString(), anyString(), anyString()))`.

### Integration tests

- `RepoDocIntegrationTest` uses three `MockWebServer` instances: GitHub, Copilot token exchange,
  Copilot completions. Completions responses must be OpenAI chat completion format
  (`choices[0].message.content`), NOT Anthropic format.

---

## Adding a new agent

1. Create `src/main/java/com/repoinsight/agent/MyNewAgent.java`
   - `@Component @RequiredArgsConstructor @Slf4j`
   - Single field: `private final LlmClient llmClient;`
   - Call `llmClient.runAgent(systemPrompt, userPrompt, context)` or
     `llmClient.runAgentWithJsonOutput(...)` for structured output
2. Wire it into `RepoAnalysisOrchestrator` if it fits the existing pipeline
3. Add a unit test in `src/test/java/com/repoinsight/agent/MyNewAgentTest.java`
   - `@Mock LlmClient llmClient;` — field name must match the agent field

---

## Adding a new static analysis component

1. Implement the interface/logic in `com.repoinsight.static_analysis`
2. Use `ParsedClass`, `ParsedMethod`, `ParsedField` as inputs (produced by `JavaAstParser`)
3. Output one of the shared model types: `BusinessFlow`, `IntegrationPoint`, `MethodCallNode`
4. Wire into `StaticAnalysisOrchestrator` which calls all static components in sequence

---

## Configuration rules

- All env vars have defaults in `application.yml` using `${ENV_VAR:default}` syntax
- **Required at runtime:** `GITHUB_TOKEN` (PAT with `repo` + `copilot` scope)
- **Optional overrides:** `COPILOT_TOKEN_URL` (GHES), `COPILOT_COMPLETIONS_URL`, `COPILOT_MODEL`,
  `COPILOT_MAX_TOKENS`, `OUTPUT_DIR`, `MAX_DEPTH`, `DB_URL`, `DB_USER`, `DB_PASS`,
  `ADMIN_USER`, `ADMIN_PASS`, `ANALYSIS_ENGINE` (per-job, not global)
- Do not add new `@Value` fields without a `:default` fallback unless the property is truly required
- `analysis.engine` does not exist in the YAML. Do not add it back.

---

## Cross-platform encoding rules

All source decoding is explicit UTF-8:
```java
new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8)
```
`-Dfile.encoding=UTF-8` is set in the Spring Boot Maven plugin JVM args.
`project.build.sourceEncoding=UTF-8` is set in `pom.xml` properties.
`SystemInfoLogger` warns at startup if the JVM default charset is not UTF-8.

Do not use `new String(bytes)` without an explicit charset anywhere in this codebase.

---

## Security rules

- Secrets are scrubbed before any source file reaches the LLM:
  `GitHubRepoFetcher.scrubSecrets()` applies regex patterns from `analysis.secret-scrub-patterns`
- `/api/**` is `permitAll()` — auth can be layered on top via gateway/proxy
- Admin Basic Auth protects actuator endpoints (`/actuator/**`)
- CSRF is disabled (stateless REST API consumed by SPA)
- Do not commit real tokens, passwords, or API keys

---

## Build and run

```bash
# Run (Copilot engine — requires GITHUB_TOKEN with copilot scope)
export GITHUB_TOKEN=ghp_...
mvn spring-boot:run

# Run (STATIC engine — no LLM needed)
export GITHUB_TOKEN=ghp_...   # still needed to fetch source from GitHub
export ANALYSIS_ENGINE=STATIC  # passed as analysisMode in request body
mvn spring-boot:run

# Unit tests only (no Docker, no network)
mvn test

# Full build
mvn clean package -DskipTests
java -Dfile.encoding=UTF-8 --enable-preview -jar target/repo-doc-gen-1.0.0-SNAPSHOT.jar
```

---

## Dependency rules

- `anthropic-java` SDK is **not** in `pom.xml`. Do not add it.
- `--enable-preview` is **not** in the compiler or surefire config. Do not add it.
- Test-scope deps present: `cucumber-java`, `cucumber-spring`, `cucumber-junit-platform-engine`,
  `testcontainers:junit-jupiter`, `testcontainers:postgresql`, `testcontainers:kafka`,
  `wiremock-standalone`, `mockwebserver`, `mockito-core`
- JavaParser (`javaparser-core`) is main-scope — used by static engine at runtime

---

## What NOT to do

- Do not add `ClaudeAgentClient`, `AnthropicConfig`, or `anthropic-java` dependency
- Do not add `@ConditionalOnProperty` to `GitHubCopilotClient` — it is unconditionally active
- Do not add `analysis.engine` back to `application.yml` — mode is per-job
- Do not use `new String(bytes)` without `StandardCharsets.UTF_8`
- Do not use `thenCallRealMethod()` on any interface mock in tests
- Do not stub `runAgentWithJsonOutput` to return `null` — always return a proper wrapper object
- Do not use `stringThat(...)` — use `argThat((String x) -> ...)`
- Do not use `when(service.createJob(anyString(), anyString()))` — the method takes 3 args
