# RepoDocGen — Technical Specification

**Version:** 1.0.0  
**Status:** Draft  
**Author:** Tech Lead  
**Last Updated:** 2026-05-19

---

## 1. Purpose & Problem Statement

Engineering teams routinely inherit or onboard to unfamiliar codebases. The cost: weeks of spelunking, tribal knowledge extraction meetings, and test coverage that lags reality.

**RepoDocGen** automates codebase archaeology. Given a GitHub repository URL, it produces:

- Human-readable **business logic documentation** explaining *what* the system does and *why*
- **Method call hierarchy trees** showing *how* logic flows end-to-end
- **Integration topology** mapping every outbound dependency (DB, Kafka, S3, cache, REST, third-party APIs)
- A complete **Gherkin test suite** covering happy paths, edge cases, and failure modes derived from the actual production code — not from hand-written guesses

---

## 2. Stakeholders

| Role | Concern |
|---|---|
| Engineering Lead | Accuracy of generated Gherkin; no hallucinated scenarios |
| Developer (onboarding) | Clarity of business flow docs; navigable call hierarchies |
| QA / SDET | Completeness of edge cases; scenario structure ready for Cucumber/JUnit 5 |
| Platform / DevOps | Deployment model; GitHub token security; output artifact storage |

---

## 3. Scope

### In Scope (v1.0)
- Java repositories (Spring Boot, Quarkus, plain Java)
- Public and private GitHub repos (via PAT)
- Output formats: Markdown, JSON (structured)
- Integrations detected: JDBC/JPA, Kafka, S3 (AWS SDK), Redis/Memcached, RestTemplate/WebClient/Feign, Retrofit, external HTTP clients
- Gherkin scenarios: business flows, DB mutations, API contracts, Kafka produce/consume, cache miss/hit, third-party failure modes

### Out of Scope (v1.0)
- Non-Java repos (Kotlin support planned v1.1)
- GitLab / Bitbucket (v1.2)
- Real-time streaming output via WebSocket (v1.1)
- IDE plugin (roadmap)

---

## 4. Architecture Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                        REST API  (Spring MVC)                     │
│  POST /api/v1/analyse   ──►  RepoDocController                   │
└───────────────────────────────────┬──────────────────────────────┘
                                    │
                    ┌───────────────▼───────────────┐
                    │   RepoDocumentationService     │
                    │   (orchestrator / saga)        │
                    └──┬────────────────────────┬───┘
                       │                        │
          ┌────────────▼──────┐     ┌───────────▼──────────┐
          │  GitHubRepoFetcher │     │  RepoAnalysisAgent   │
          │  (GitHub REST API) │     │  (Agent orchestrator)│
          └────────────┬──────┘     └──────────────────────┘
                       │                        │
           ┌───────────▼───────────┐            │  Claude API
           │  Raw Source Files     │    ┌───────▼──────────────────┐
           │  (in-memory / temp)   │    │  Parallel Sub-Agents      │
           └───────────┬───────────┘    │  ┌─────────────────────┐ │
                       │               │  │ BusinessLogicAgent   │ │
                       └──────────────►│  ├─────────────────────┤ │
                                       │  │ MethodHierarchyAgent │ │
                                       │  ├─────────────────────┤ │
                                       │  │ IntegrationAgent     │ │
                                       │  ├─────────────────────┤ │
                                       │  │ GherkinAgent         │ │
                                       │  └─────────────────────┘ │
                                       └──────────┬───────────────┘
                                                  │
                                    ┌─────────────▼──────────────┐
                                    │   DocumentationGenerator    │
                                    │   (Markdown / JSON output)  │
                                    └────────────────────────────┘
```

---

## 5. Agent Design

Each sub-agent is a focused Claude call with a structured prompt and a typed output schema.

### 5.1 BusinessLogicAgent

**Input:** All service/domain classes (filtered by package heuristics)  
**Output:**
```json
{
  "flows": [
    {
      "name": "CreateOrderFlow",
      "trigger": "POST /api/orders",
      "description": "...",
      "steps": ["validate cart", "reserve inventory", "charge payment", "emit OrderCreated event"],
      "invariants": ["cart must not be empty", "SKU stock >= quantity"]
    }
  ]
}
```

### 5.2 MethodHierarchyAgent

**Input:** Entry-point methods (controllers, consumers, schedulers)  
**Output:** Call tree with depth, cross-cutting concerns (transactions, security, retry)

```
OrderController.createOrder(CreateOrderRequest)
  └── OrderService.createOrder(OrderDto)
        ├── InventoryClient.reserve(List<LineItem>)     [Feign → inventory-service]
        ├── PaymentGateway.charge(ChargeRequest)        [HTTP → Stripe]
        ├── orderRepository.save(Order)                 [JPA → PostgreSQL]
        └── kafkaTemplate.send("order.created", event) [Kafka producer]
```

### 5.3 IntegrationAgent

Detects and classifies every outbound I/O:

| Category | Detection Pattern |
|---|---|
| JDBC / JPA | `@Repository`, `JdbcTemplate`, `EntityManager`, Spring Data interfaces |
| Kafka Producer | `KafkaTemplate.send()`, `@KafkaListener` |
| Kafka Consumer | `@KafkaListener`, `ConsumerRecord` |
| S3 | `AmazonS3`, `S3Client`, `S3Template` |
| Redis / Cache | `@Cacheable`, `RedisTemplate`, `CacheManager` |
| HTTP / REST | `RestTemplate`, `WebClient`, `FeignClient`, `Retrofit` |
| Third-party SDK | AWS SDK, Stripe, Twilio, SendGrid patterns |

### 5.4 GherkinAgent

**Input:** BusinessLogicAgent + IntegrationAgent outputs  
**Output:** `.feature` files covering:

- Happy path for every business flow
- Boundary conditions (empty collections, max values, zero amounts)
- DB: record not found (404 vs 500), optimistic lock conflict, constraint violation
- Kafka: producer timeout, consumer poison pill / DLQ routing
- S3: object not found, upload failure, presigned URL expiry
- Cache: cache miss → DB fallback, cache eviction during concurrent request
- HTTP / third-party: 4xx client error, 5xx server error, timeout, retry exhaustion
- Authorization: unauthenticated, forbidden role, token expiry

---

## 6. Data Flow

```
GitHub Token  ──►  GitHubApiClient ──►  repo tree + file contents
                                                │
                              ┌─────────────────▼───────────────┐
                              │        FileBatchBuilder         │
                              │  chunk by token budget           │
                              │  filter: .java only              │
                              │  exclude: test/, generated/      │
                              └─────────────────┬───────────────┘
                                                │
                              ┌─────────────────▼───────────────┐
                              │     ClaudeApiClient              │
                              │  model: claude-sonnet-4-6        │
                              │  prompt caching: enabled         │
                              │  max_tokens: 8192 per agent call │
                              └─────────────────────────────────┘
```

---

## 7. API Contract

### POST `/api/v1/analyse`

```json
// Request
{
  "repoUrl": "https://github.com/org/repo",
  "branch": "main",
  "githubToken": "ghp_...",     // optional; uses env var if absent
  "options": {
    "includeTests": false,       // include test/ sources in analysis
    "outputFormat": "MARKDOWN",  // MARKDOWN | JSON | BOTH
    "maxDepth": 5                // method call tree depth
  }
}

// Response 202 Accepted
{
  "jobId": "a1b2c3d4",
  "status": "QUEUED",
  "pollUrl": "/api/v1/jobs/a1b2c3d4"
}
```

### GET `/api/v1/jobs/{jobId}`

```json
{
  "jobId": "a1b2c3d4",
  "status": "COMPLETED",  // QUEUED | RUNNING | COMPLETED | FAILED
  "artifacts": {
    "businessLogic": "/api/v1/jobs/a1b2c3d4/artifacts/business-logic.md",
    "methodHierarchy": "/api/v1/jobs/a1b2c3d4/artifacts/method-hierarchy.md",
    "integrations": "/api/v1/jobs/a1b2c3d4/artifacts/integrations.md",
    "gherkin": "/api/v1/jobs/a1b2c3d4/artifacts/tests/"
  },
  "stats": {
    "filesAnalysed": 142,
    "tokensUsed": 187430,
    "durationMs": 34210
  }
}
```

---

## 8. Non-Functional Requirements

| Requirement | Target |
|---|---|
| Max repo size | 500 source files / 5 MB raw Java |
| P95 latency (medium repo ~100 files) | < 60 seconds |
| Claude token budget per job | 200k input + 16k output |
| GitHub API rate limit handling | Retry with exponential back-off |
| Secret handling | GitHub token never logged; masked in responses |
| Idempotency | Same repo + branch + commit SHA → cached result (1 hour TTL) |

---

## 9. Technology Stack

| Layer | Choice | Rationale |
|---|---|---|
| Runtime | Java 21 (virtual threads) | Structured concurrency for parallel agent calls |
| Framework | Spring Boot 3.3 | Production-grade, well-understood in enterprise Java |
| Build | Maven 3.9 | Reproducible builds; common in enterprise |
| HTTP client | Spring WebClient (reactive) | Non-blocking GitHub + Claude API calls |
| Claude SDK | Anthropic Java SDK | Official SDK; prompt caching; structured outputs |
| Serialization | Jackson 2.17 | Industry standard |
| Persistence | Spring Data JPA + H2 (dev) / PostgreSQL (prod) | Job state, cached results |
| Config | Spring Config + env vars | 12-factor compliant |
| Observability | Micrometer + Spring Actuator | Metrics, health, tracing hooks |
| Testing | JUnit 5, Mockito, Testcontainers, Cucumber | Unit, integration, contract |

---

## 10. Security Considerations

- GitHub PAT accepted only over HTTPS; never stored in plaintext (encrypted at rest)
- Claude prompts must not include secrets — strip tokens/keys from source files before sending
- Rate limiting on `/api/v1/analyse` (10 req/min per IP default)
- Job artifacts scoped to requester (Bearer token auth)

---

## 11. Milestones

| Milestone | Deliverable | Target |
|---|---|---|
| M1 | GitHub ingestion + raw file extraction working | Week 1 |
| M2 | BusinessLogicAgent + MethodHierarchyAgent producing Markdown | Week 2 |
| M3 | IntegrationAgent classifying all I/O patterns | Week 3 |
| M4 | GherkinAgent generating complete `.feature` files | Week 4 |
| M5 | Job queue, async polling API, artifact storage | Week 5 |
| M6 | Observability, rate limiting, secret scrubbing, prod hardening | Week 6 |

---

## 12. Open Questions

- [ ] Should generated Gherkin be runnable (stub step definitions auto-generated) or documentation-only?
- [ ] Token cost cap per job — hard limit or warning + user override?
- [ ] Multi-module Maven/Gradle projects: analyse root aggregator or each module independently?
- [ ] Output storage: local filesystem (v1) vs S3/GCS (v1.1)?
