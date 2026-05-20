# RepoDocGen

Documentation and Gherkin test coverage intelligence for Java repositories.

Point it at any GitHub Java repo and get structured business logic docs, method call hierarchies, integration topology maps, and complete Gherkin feature files with typed step definitions. A second mode compares your dev repo against a QA test repo and produces a per-class coverage report (COVERED / PARTIAL / MISSED / NOT_NEEDED) with suggested Gherkin for every gap.

Three analysis engines are available: **GitHub Copilot** (default, enterprise GHES supported), **Anthropic Claude** (optional fallback), and **Static** (JavaParser AST, fully offline — no LLM required).

---

## Table of Contents

- [Features](#features)
- [Quick Start](#quick-start)
- [Engine Comparison](#engine-comparison)
- [GitHub Enterprise Server (GHES)](#github-enterprise-server-ghes)
- [Environment Variables](#environment-variables)
- [API Reference](#api-reference)
- [Generated Artefacts](#generated-artefacts)
- [QA Coverage Analysis](#qa-coverage-analysis)
- [Production Deployment](#production-deployment)
- [Architecture](#architecture)
- [Development & Tests](#development--tests)
- [Security Notes](#security-notes)
- [Performance Limits](#performance-limits)

---

## Features

- Business logic narrative (what the system does, step by step, per public flow)
- Method call hierarchy trees
- Integration topology — detects and maps DB (JPA / JdbcTemplate), Kafka, S3, Redis/Cache, HTTP (RestTemplate / WebClient / Feign), and third-party SDKs
- Complete Gherkin `.feature` files covering happy paths, boundary conditions, auth failures, and infrastructure failures
- Typed step definition classes generated per integration — JdbcTemplate, `@EmbeddedKafka`, `@MockBean S3Client`, WireMockServer helpers
- QA gap analysis: compares a dev repo against a QA repo, matching Gherkin English to Java methods using verb synonym groups and step definition annotation scanning
- Static analysis mode using JavaParser AST — produces identical output shape with no API key
- SPA web UI (Tailwind CSS + Alpine.js + Chart.js) — no build step required
- Cross-platform: explicit UTF-8 throughout; works identically on Windows, macOS, and Linux

---

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.9+ (or use the included `mvn` wrapper)
- A GitHub Personal Access Token

### Engine 1 — GitHub Copilot (default)

Requires a GitHub PAT with the `copilot` scope.

```bash
GITHUB_TOKEN=ghp_yourtoken mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8083
```

Open `http://localhost:8083`.

---

### Engine 2 — Static / offline (no LLM)

Uses JavaParser AST only. No AI API key required. Still needs a GitHub token to fetch source files.

```bash
export ANALYSIS_ENGINE=STATIC
export GITHUB_TOKEN=ghp_yourtoken

mvn spring-boot:run
```

---

## Engine Comparison

| | COPILOT (default) | CLAUDE | STATIC |
|---|---|---|---|
| Requires API key | GitHub PAT + copilot scope | `ANTHROPIC_API_KEY` | — |
| Narrative quality | High | High | Template-based |
| Gherkin quality | High | High | Template-based |
| Coverage analysis | AI-powered | AI-powered | Heuristic synonym matching |
| Offline capable | No | No | Source-fetch only needs GitHub token |
| Enterprise GHES | Yes — `COPILOT_TOKEN_URL` | N/A | N/A |
| Prompt caching | No | Yes (Anthropic cache API) | N/A |

---

## GitHub Enterprise Server (GHES)

Override the Copilot token exchange URL to point at your GHES instance:

```bash
export COPILOT_TOKEN_URL=https://github.mycompany.com/api/v3/copilot_internal/v2/token
export GITHUB_TOKEN=ghp_yourenterprisetoken

mvn spring-boot:run
```

The completions endpoint (`COPILOT_COMPLETIONS_URL`) typically stays as the default `https://api.githubcopilot.com/chat/completions` even for GHES, but it is also overridable.

---

## Environment Variables

### Core

| Variable | Required | Default | Description |
|---|---|---|---|
| `GITHUB_TOKEN` | **Yes** | — | GitHub PAT. Needs `repo` (read) + `copilot` scope when `ANALYSIS_ENGINE=COPILOT` |
| `ANALYSIS_ENGINE` | No | `COPILOT` | `COPILOT`, `CLAUDE`, or `STATIC` |
| `OUTPUT_DIR` | No | `<system tmpdir>/repo-doc-gen` | Directory where generated artefacts are written |
| `MAX_DEPTH` | No | `5` | Maximum AST traversal depth |

### GitHub Copilot (engine = COPILOT)

| Variable | Required | Default | Description |
|---|---|---|---|
| `COPILOT_TOKEN_URL` | No | `https://api.github.com/copilot_internal/v2/token` | Token exchange URL — override for GHES |
| `COPILOT_COMPLETIONS_URL` | No | `https://api.githubcopilot.com/chat/completions` | Completions endpoint |
| `COPILOT_MODEL` | No | `gpt-4o` | Copilot model identifier |
| `COPILOT_MAX_TOKENS` | No | `4096` | Max tokens per completion |

### Anthropic Claude (engine = CLAUDE)

| Variable | Required | Default | Description |
|---|---|---|---|
| `ANTHROPIC_API_KEY` | **Yes** (when `engine=CLAUDE`) | — | Anthropic API key |

### Database

| Variable | Required | Default | Description |
|---|---|---|---|
| `DB_URL` | No | `jdbc:h2:mem:repoinsight` | JDBC connection URL |
| `DB_DRIVER` | No | `org.h2.Driver` | JDBC driver class |
| `DB_USER` | No | `sa` | Database username |
| `DB_PASS` | No | *(empty)* | Database password |
| `DDL_AUTO` | No | `update` | Hibernate DDL strategy (`validate` recommended in production) |
| `HIBERNATE_DIALECT` | No | `org.hibernate.dialect.H2Dialect` | Set to `org.hibernate.dialect.PostgreSQLDialect` for PostgreSQL |

### Admin / Security

| Variable | Required | Default | Description |
|---|---|---|---|
| `ADMIN_USER` | No | `admin` | Basic auth username for actuator endpoints |
| `ADMIN_PASS` | No | `changeme` | Basic auth password — **change in production** |

---

## API Reference

All endpoints live under `/api/v1`. The SPA at `/` calls these automatically.

### Documentation Generation

#### Submit a job

```
POST /api/v1/analyse
Content-Type: application/json

{
  "repoUrl": "https://github.com/org/repo",
  "branch": "main"
}
```

Response:
```json
{
  "id": "a1b2c3d4-...",
  "status": "QUEUED",
  "repoUrl": "https://github.com/org/repo",
  "branch": "main",
  "createdAt": "2026-01-15T09:30:00Z"
}
```

#### Poll job status

```
GET /api/v1/jobs/{id}
```

Status values: `QUEUED` → `RUNNING` → `DONE` | `FAILED`

#### Download artefacts (once status = DONE)

```
GET /api/v1/jobs/{id}/docs          → Business logic Markdown
GET /api/v1/jobs/{id}/hierarchy     → Method call hierarchy Markdown
GET /api/v1/jobs/{id}/gherkin       → Feature files + step definitions (ZIP)
GET /api/v1/jobs/{id}/integrations  → Integration topology JSON
```

### QA Coverage Analysis

#### Submit a coverage comparison job

```
POST /api/v1/coverage/compare
Content-Type: application/json

{
  "devRepoUrl": "https://github.com/org/service",
  "devBranch": "main",
  "qaRepoUrl": "https://github.com/org/service-tests",
  "qaBranch": "main"
}
```

#### Poll coverage job

```
GET /api/v1/coverage/jobs/{id}
```

#### Get the coverage report

```
GET /api/v1/coverage/jobs/{id}/report
```

Response shape:
```json
{
  "summary": "62% of testable classes have full or partial coverage.",
  "score": 62,
  "classes": [
    {
      "className": "OrderService",
      "status": "PARTIAL",
      "coveredMethods": ["createOrder", "cancelOrder"],
      "missedMethods": ["updateOrder"],
      "explanation": "Happy-path order creation and cancellation are covered. Update path has no QA scenario.",
      "suggestedGherkin": "Scenario: Update order details\n  Given an existing order..."
    }
  ]
}
```

---

## Generated Artefacts

### Feature files

Standard Gherkin (`.feature`) with scenarios covering:

- Happy path
- Validation / bad input (HTTP 400)
- Authentication failure (HTTP 401)
- Authorisation failure (HTTP 403)
- Not found (HTTP 404)
- Infrastructure failure (DB unavailable, Kafka producer error, S3 error)
- Concurrency / idempotency edge cases

### Step definition classes

One Java class per feature, with conditional imports and typed helpers based on the integrations detected in the repo:

| Integration detected | Generated helper |
|---|---|
| JPA / JdbcTemplate | `JdbcTemplate` setup and teardown |
| Kafka | `@EmbeddedKafka` + `KafkaTestUtils` consumer |
| S3 / AmazonS3 | `@MockBean S3Client` with Mockito stubs |
| Cache / Redis | `CacheManager` injection |
| HTTP / REST / Feign | `WireMockServer` stubs |
| Concurrency | `ExecutorService` + countdown latch helpers |

Classes are annotated with `@CucumberContextConfiguration`, `@SpringBootTest`, and `@ActiveProfiles("test")`.

---

## QA Coverage Analysis

The coverage analyser fetches test files from the QA repo matching any of:

`*Test.java`, `*Tests.java`, `*Spec.java`, `*IT.java`, `*Steps.java`, `*StepDefs.java`, `*StepDefinitions.java`, `*TestCase.java`, `*E2ETest.java`, `*IntegrationTest.java`, `*.feature`

Coverage matching uses three layers to avoid false MISSED reports from natural-language Gherkin:

1. **camelCase word segments** — `createOrder` splits to `[create, order]`; both must appear in the Gherkin scenario text
2. **Verb synonym groups** — `create`, `add`, `place`, `submit`, `save`, `register` all match `createOrder`; `get`, `find`, `fetch`, `load`, `retrieve`, `list` all match `findById`
3. **Step definition annotation scanning** — `@When`, `@Given`, `@Then` annotation values are scanned directly; method body is also checked for `.methodName(` calls

The score formula excludes `NOT_NEEDED` classes (DTOs, `@Configuration`, simple getters/setters, exception subclasses) from the denominator.

---

## Production Deployment

### Build

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

Use Flyway or Liquibase for schema migrations in production (`DDL_AUTO=validate`).

### Health check

```
GET /actuator/health
```

Prometheus metrics: `GET /actuator/prometheus`

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
┌──────────▼──────────┐   ┌─────────▼──────────────────┐
│  RepoDocumentation  │   │  CoverageComparisonService  │
│  Service            │   │                             │
└──────────┬──────────┘   └─────────┬──────────────────┘
           │                         │
┌──────────▼─────────────────────────▼──────────────────┐
│                 LlmClient (interface)                  │
│                                                        │
│  GitHubCopilotClient     ClaudeAgentClient             │
│  (default / GHES)        (engine=CLAUDE only)          │
│                                                        │
│  StaticEngine (JavaParser AST — no LLM, offline)       │
└──────────┬─────────────────────────┬──────────────────┘
           │                         │
┌──────────▼──────┐       ┌──────────▼──────────┐
│ GitHubRepoFetcher│       │   QARepoFetcher     │
│ (*.java files)  │       │   (test files only) │
└──────────┬──────┘       └──────────┬──────────┘
           │                         │
┌──────────▼─────────────────────────▼──────────────────┐
│               GitHub REST API v3                       │
│         git/trees · git/blobs · rate-limit             │
└────────────────────────────────────────────────────────┘
```

**Multi-agent pipeline (AI modes)**

```
Tier 1 (parallel):
  BusinessLogicAgent
  MethodHierarchyAgent  →  Tier 2: GherkinAgent
  IntegrationAgent

Coverage:
  CoverageComparisonAgent (dev classes × QA test files)
```

**Static mode** replaces all agents with:

```
JavaAstParser → ParsedClass / ParsedMethod
  └─ StaticBusinessLogicExtractor   (flows, triggers, invariants)
  └─ StaticIntegrationDetector      (DB / Kafka / S3 / Cache / HTTP)
  └─ StaticMethodHierarchyBuilder   (call graph)
  └─ TemplateGherkinGenerator       (feature + step def from templates)
  └─ StaticCoverageAnalyser         (synonym + annotation matching)
```

---

## Project Structure

```
src/
├── main/
│   ├── java/com/repoinsight/
│   │   ├── agent/              # AI sub-agents (BusinessLogic, MethodHierarchy, Integration, Gherkin, Coverage)
│   │   ├── claude/             # Anthropic SDK wrapper (ClaudeAgentClient — optional)
│   │   ├── llm/                # LlmClient interface + GitHubCopilotClient (default)
│   │   ├── config/             # Spring config (WebClient, Anthropic, async executor, SystemInfoLogger)
│   │   ├── controller/         # REST API controllers
│   │   ├── coverage/           # Coverage comparison service + QARepoFetcher
│   │   ├── github/             # GitHub API client + secret scrubber
│   │   ├── service/            # Job orchestration (JPA-backed)
│   │   └── static_analysis/    # JavaParser AST engine (StaticEngine, parsers, generators)
│   └── resources/
│       ├── application.yml
│       └── static/index.html   # SPA (no build step)
└── test/
    └── java/com/repoinsight/
        ├── static_analysis/
        │   ├── JavaAstParserTest.java
        │   ├── StaticIntegrationDetectorTest.java
        │   └── StaticBusinessLogicExtractorTest.java
        └── coverage/service/
            └── StaticCoverageAnalyserTest.java
```

---

## Development & Tests

```bash
# Run unit tests (H2 in-memory, no Docker required)
mvn test

# Full verify including integration tests (requires Docker for Testcontainers)
mvn verify
```

Test coverage includes:

- `JavaAstParserTest` — layer detection, method/field parsing, class metadata, edge cases
- `StaticIntegrationDetectorTest` — DB (JPA/JdbcTemplate), Kafka, S3, Cache, HTTP, third-party SDK detection
- `StaticBusinessLogicExtractorTest` — flow trigger detection, step derivation, invariants, side effects
- `StaticCoverageAnalyserTest` — COVERED/PARTIAL/MISSED/NOT_NEEDED logic, synonym matching, score formula, suggested Gherkin

---

## Security Notes

| Concern | What is done |
|---|---|
| Secret scrubbing | All source fetched from GitHub is scanned against configurable regex patterns before any LLM call; credentials and tokens are replaced with `[REDACTED]` |
| Data residency | Source is processed in-memory and in `OUTPUT_DIR` only; nothing is sent to any third party beyond the configured LLM endpoint |
| Default credentials | `admin` / `changeme` out of the box — **override `ADMIN_USER` and `ADMIN_PASS` for any non-local deployment** |
| CSRF | Disabled for the stateless REST API; re-enable if you add session-based authentication |
| File size limits | Per-file cap at 100 KB; per-repo cap at 500 files — prevents runaway memory usage on very large monorepos |

---

## Performance Limits

| Parameter | Default | Config key |
|---|---|---|
| Files fetched per repo | 500 | `github.max-files-per-repo` |
| Max file size | 100 KB | `github.max-file-size-bytes` |
| Concurrent GitHub fetches | 8 | hardcoded concurrency in `Flux.flatMap(…, 8)` |
| LLM timeout | 120 s | `github.copilot.timeout-seconds` / `anthropic.timeout-seconds` |
| Async thread pool | 4 core / 20 max / 100 queue | `spring.task.execution.pool.*` |
| Analysis cache TTL | 60 min | `analysis.cache-ttl-minutes` |

---

## License

MIT
