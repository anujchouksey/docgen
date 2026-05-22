# RepoDocGen

AI-powered documentation and BDD test coverage intelligence for Java repositories.

Point it at any GitHub repo (or a local folder) and get structured business logic docs, method call hierarchies, integration topology maps, and complete Gherkin feature files. A second mode compares your Spring Boot dev repo against a Cucumber BDD QA repo and produces a per-class coverage report (COVERED / PARTIAL / MISSED / NOT_NEEDED) with suggested Gherkin for every gap.

---

## Run It

```bash
GITHUB_TOKEN=ghp_yourtoken mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8083
```

Then open **http://localhost:8083**

> **No GitHub token / no Copilot?** Select **Local Folder** in the UI and switch mode to **Static** — fully offline, zero config needed.

---

## Table of Contents

- [Features](#features)
- [Analysis Engines](#analysis-engines)
- [Local Folder Support](#local-folder-support)
- [QA Coverage Analysis](#qa-coverage-analysis)
- [Environment Variables](#environment-variables)
- [API Reference](#api-reference)
- [Generated Artefacts](#generated-artefacts)
- [Production Deployment](#production-deployment)
- [Architecture](#architecture)
- [Development & Tests](#development--tests)
- [Security Notes](#security-notes)
- [Performance Limits](#performance-limits)

---

## Features

- Business logic narrative — what the system does, step by step, per public flow
- Method call hierarchy trees
- Integration topology — detects DB (JPA / JdbcTemplate), Kafka, S3, Redis/Cache, HTTP (RestTemplate / WebClient / Feign), and third-party SDKs
- Complete Gherkin `.feature` files covering happy paths, boundary conditions, auth failures, and infrastructure failures
- Typed step definition classes generated per integration (JdbcTemplate, `@EmbeddedKafka`, `@MockBean S3Client`, WireMockServer helpers)
- BDD QA gap analysis — deeply compares a Cucumber BDD test repo against a Spring Boot dev repo using semantic / fuzzy matching (edit distance, subsequence detection, verb synonym groups)
- Static analysis mode using JavaParser AST — identical output shape, no LLM, no API key
- Local folder path support — skip GitHub entirely, point at any local clone
- SPA web UI (Tailwind CSS + Alpine.js + Chart.js) — no build step
- Cross-platform: explicit UTF-8 throughout, works on Windows / macOS / Linux

---

## Analysis Engines

| | COPILOT (default) | STATIC (offline) |
|---|---|---|
| Requires API key | GitHub PAT + copilot scope | — |
| Narrative quality | AI-generated prose | Template-based |
| Gherkin quality | AI-generated | Template-based |
| Coverage analysis | AI semantic matching | Heuristic + DeepBddMatcher |
| Offline capable | No | Yes (local folder mode) |
| Enterprise GHES | Yes — override `COPILOT_TOKEN_URL` | N/A |

### GitHub Copilot (default)

```bash
GITHUB_TOKEN=ghp_yourtoken mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8083
```

### Static / offline (no LLM, no API key)

Select **Static** in the UI mode dropdown, or set the env variable:

```bash
ANALYSIS_ENGINE=STATIC GITHUB_TOKEN=ghp_yourtoken mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8083
```

### GitHub Enterprise Server (GHES)

```bash
COPILOT_TOKEN_URL=https://github.mycompany.com/api/v3/copilot_internal/v2/token \
GITHUB_TOKEN=ghp_yourenterprisetoken \
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8083
```

---

## Local Folder Support

Both the **Analyser** and **Coverage** tabs accept an absolute local path in place of a GitHub URL.

| Input | Example |
|---|---|
| macOS / Linux | `/Users/me/projects/my-service` |
| Windows | `C:\Projects\my-service` |
| File URI | `file:///Users/me/projects/my-service` |

When you click **Local Folder** in the UI the mode automatically switches to **Static** and the GitHub Token field is hidden — no token, no Copilot, no network needed.

---

## QA Coverage Analysis

Point the **Coverage** tab at your Spring Boot dev repo (source) and your Cucumber BDD QA repo (tests). The engine:

1. **Phase 1 — BDD QA deep parse**: reads every `.feature` file (scenarios, tags, steps) and every step-definition `.java` file (`@Given/@When/@Then` patterns + method bodies) into a structured index.
2. **Phase 2 — dev class comparison**: matches each public method in each Spring Boot class against the BDD index using a multi-signal scoring engine.

### Semantic / fuzzy matching (DeepBddMatcher)

BDD step text and Java method names rarely use identical words. The engine applies:

| Signal | Example | Score |
|---|---|---|
| Verb exact match | step has `"validate"`, method is `validate…` | 40 pts |
| Verb synonym group | step has `"check"`, method is `validate…` | 28 pts |
| Verb stem match | step has `"validates"`, method is `validate…` | 38 pts |
| Entity edit-distance ≤ 1 | step token `"xyz"`, method token `"xayz"` | 10 pts |
| Entity subsequence | `"xyz"` chars appear in order in `"xayz"` | 8 pts |
| Entity edit-distance ≤ 2 | | 6 pts |
| Entity substring | | 4 pts |
| Direct name in text | method name appears verbatim | +10 pts bonus |
| **Match threshold** | | **50 pts** |

**Walk-through of the canonical example:**
- BDD step: `"When the system validates the xyz configuration"`
- Dev method: `validateXayz()`
- Verb `"validate"` == `"validate"` → **40 pts**
- Entity token `"xayz"` vs `"xyz"`: edit distance = 1 → **10 pts**
- Total: **50 → MATCH ✓** (would have been MISSED with exact-string matching)

Synonym groups cover 20+ verb families: `create/add/save/register/post`, `get/find/fetch/load/retrieve/list`, `update/edit/modify/patch`, `delete/remove/cancel/archive`, `validate/check/verify/assert/confirm`, `process/handle/execute/run`, `send/publish/emit/dispatch`, and more.

### Coverage status rules

| Status | Meaning |
|---|---|
| **COVERED** | Every significant public method has a happy-path scenario AND at least one edge/failure scenario |
| **PARTIAL** | Some methods or failure modes are missing |
| **MISSED** | No Gherkin scenario or step def semantically exercises this class |
| **NOT_NEEDED** | DTOs, `@Configuration`, exception subclasses, boilerplate-only classes |

---

## Environment Variables

### Core

| Variable | Required | Default | Description |
|---|---|---|---|
| `GITHUB_TOKEN` | Yes (GitHub mode) | — | GitHub PAT. Needs `repo` (read) + `copilot` scope for Copilot engine |
| `ANALYSIS_ENGINE` | No | `COPILOT` | `COPILOT` or `STATIC` |
| `OUTPUT_DIR` | No | `<tmpdir>/repo-doc-gen` | Directory for generated artefacts |
| `MAX_DEPTH` | No | `5` | Maximum AST traversal depth |

### GitHub Copilot

| Variable | Required | Default | Description |
|---|---|---|---|
| `COPILOT_TOKEN_URL` | No | `https://api.github.com/copilot_internal/v2/token` | Override for GHES |
| `COPILOT_COMPLETIONS_URL` | No | `https://api.githubcopilot.com/chat/completions` | Completions endpoint |
| `COPILOT_MODEL` | No | `gpt-4o` | Model identifier |
| `COPILOT_MAX_TOKENS` | No | `4096` | Max tokens per completion |

### Database

| Variable | Required | Default | Description |
|---|---|---|---|
| `DB_URL` | No | `jdbc:h2:mem:repoinsight` | JDBC connection URL |
| `DB_DRIVER` | No | `org.h2.Driver` | JDBC driver class |
| `DB_USER` | No | `sa` | Database username |
| `DB_PASS` | No | *(empty)* | Database password |
| `DDL_AUTO` | No | `update` | Hibernate DDL strategy |
| `HIBERNATE_DIALECT` | No | `org.hibernate.dialect.H2Dialect` | Set to `PostgreSQLDialect` for Postgres |

### Admin / Security

| Variable | Required | Default | Description |
|---|---|---|---|
| `ADMIN_USER` | No | `admin` | Basic auth username for actuator endpoints |
| `ADMIN_PASS` | No | `changeme` | Basic auth password — **change in production** |

---

## API Reference

All endpoints under `/api/v1`. The SPA calls these automatically.

### Documentation Generation

```
POST /api/v1/analyse
{ "repoUrl": "https://github.com/org/repo", "branch": "main" }

GET  /api/v1/jobs/{id}               → job status (QUEUED → RUNNING → DONE | FAILED)
GET  /api/v1/jobs/{id}/docs          → business logic Markdown
GET  /api/v1/jobs/{id}/hierarchy     → method call hierarchy Markdown
GET  /api/v1/jobs/{id}/gherkin       → feature files + step definitions (ZIP)
GET  /api/v1/jobs/{id}/integrations  → integration topology JSON
```

Local folder: use the absolute path as `repoUrl` (e.g. `"/Users/me/my-service"`).

### QA Coverage Analysis

```
POST /api/v1/coverage/compare
{
  "devRepoUrl": "https://github.com/org/service",   // or local path
  "devBranch": "main",
  "qaRepoUrl":  "https://github.com/org/service-bdd",  // or local path
  "qaBranch":  "main"
}

GET  /api/v1/coverage/jobs/{id}         → job status
GET  /api/v1/coverage/jobs/{id}/report  → full coverage report JSON
```

---

## Generated Artefacts

### Feature files

Standard Gherkin (`.feature`) scenarios covering:
- Happy path
- Validation / bad input (HTTP 400)
- Authentication failure (HTTP 401)
- Authorisation failure (HTTP 403)
- Not found (HTTP 404)
- Infrastructure failure (DB unavailable, Kafka producer error, S3 error)
- Concurrency / idempotency edge cases

### Step definition classes

One Java class per feature, with typed helpers based on detected integrations:

| Integration detected | Generated helper |
|---|---|
| JPA / JdbcTemplate | `JdbcTemplate` setup / teardown |
| Kafka | `@EmbeddedKafka` + `KafkaTestUtils` consumer |
| S3 / AmazonS3 | `@MockBean S3Client` with Mockito stubs |
| Cache / Redis | `CacheManager` injection |
| HTTP / REST / Feign | `WireMockServer` stubs |
| Concurrency | `ExecutorService` + countdown latch helpers |

Classes are annotated with `@CucumberContextConfiguration`, `@SpringBootTest`, and `@ActiveProfiles("test")`.

---

## Production Deployment

```bash
mvn clean package -DskipTests

java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
     --enable-preview \
     -jar target/repo-doc-gen-1.0.0-SNAPSHOT.jar
```

### PostgreSQL

```bash
export DB_URL=jdbc:postgresql://db-host:5432/repoinsight
export DB_DRIVER=org.postgresql.Driver
export DB_USER=repoinsight
export DB_PASS=strongpassword
export DDL_AUTO=validate
export HIBERNATE_DIALECT=org.hibernate.dialect.PostgreSQLDialect
```

### Health check

```
GET /actuator/health
GET /actuator/prometheus
```

Actuator endpoints require Basic Auth (`ADMIN_USER` / `ADMIN_PASS`).

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                   SPA  (index.html)                      │
│         Tailwind CSS · Alpine.js · Chart.js · marked.js  │
└─────────────────────────┬────────────────────────────────┘
                          │ REST / JSON
┌─────────────────────────▼────────────────────────────────┐
│          AnalysisController   CoverageController         │
└──────────┬─────────────────────────┬─────────────────────┘
           │ @Async                  │ @Async
┌──────────▼──────────┐   ┌──────────▼─────────────────────┐
│  RepoDocumentation  │   │  CoverageComparisonService      │
│  Service            │   │                                 │
└──────────┬──────────┘   └──────────┬──────────────────────┘
           │                         │
┌──────────▼─────────────────────────▼──────────────────────┐
│                 LlmClient (interface)                      │
│   GitHubCopilotClient (default · GHES)                    │
│   StaticEngine (JavaParser AST — offline, no LLM)         │
└──────────┬─────────────────────────┬──────────────────────┘
           │                         │
┌──────────▼──────────┐   ┌──────────▼──────────────────────┐
│  GitHubRepoFetcher  │   │  QARepoFetcher                  │
│  LocalRepoFetcher   │   │  LocalRepoFetcher               │
└──────────┬──────────┘   └──────────┬──────────────────────┘
           │                         │
┌──────────▼─────────────────────────▼──────────────────────┐
│         GitHub REST API v3  OR  local filesystem           │
└────────────────────────────────────────────────────────────┘
```

**Static mode pipeline:**

```
JavaAstParser → ParsedClass / ParsedMethod
  └─ StaticBusinessLogicExtractor
  └─ StaticIntegrationDetector
  └─ StaticMethodHierarchyBuilder
  └─ TemplateGherkinGenerator
  └─ StaticCoverageAnalyser
       └─ BddQaAnalyser   (Phase 1: full BDD index)
       └─ DeepBddMatcher  (Phase 2: semantic method matching)
```

---

## Development & Tests

```bash
# Unit tests — H2 in-memory, no Docker required
mvn test

# Full verify including integration tests (requires Docker for Testcontainers)
mvn verify
```

---

## Security Notes

| Concern | What is done |
|---|---|
| Secret scrubbing | All source fetched from GitHub is scanned against configurable regex patterns before any LLM call; credentials are replaced with `[REDACTED]` |
| Data residency | Source is processed in-memory and in `OUTPUT_DIR` only; nothing is sent beyond the configured LLM endpoint |
| Default credentials | `admin` / `changeme` — **override `ADMIN_USER` and `ADMIN_PASS` for any non-local deployment** |
| CSRF | Disabled for the stateless REST API |
| File size limits | 100 KB per file, 500 files per repo — prevents runaway memory usage |

---

## Performance Limits

| Parameter | Default | Config key |
|---|---|---|
| Files per repo | 500 | `github.max-files-per-repo` |
| Max file size | 100 KB | `github.max-file-size-bytes` |
| Concurrent GitHub fetches | 8 | `Flux.flatMap(…, 8)` |
| LLM timeout | 120 s | `github.copilot.timeout-seconds` |
| Async thread pool | 4 core / 20 max / 100 queue | `spring.task.execution.pool.*` |
| Analysis cache TTL | 60 min | `analysis.cache-ttl-minutes` |

---

## License

MIT
