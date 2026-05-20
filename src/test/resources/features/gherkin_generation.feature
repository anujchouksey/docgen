@gherkin-generation
Feature: Gherkin Test Suite Generation

  As a QA engineer
  I want the GherkinAgent to generate complete, runnable Gherkin feature files
  So that I don't have to write them manually from scratch

  Background:
    Given the GherkinAgent is instantiated with a mocked ClaudeAgentClient
    And the following business flow exists:
      | field        | value                     |
      | name         | PlaceOrderFlow            |
      | trigger      | POST /api/orders          |
      | description  | Customer places an order  |
    And the following integration points are registered:
      | category | name                        | direction |
      | DB       | PostgreSQL (JPA)            | WRITE     |
      | KAFKA    | Kafka topic: order.created  | PRODUCE   |
      | HTTP     | Stripe Payment Gateway      | WRITE     |
      | CACHE    | Redis (order cache)         | BOTH      |

  # ─── Coverage Completeness ─────────────────────────────────────────────────

  @happy-path
  Scenario: Happy path scenario is always generated
    When generateFeatures is called with PlaceOrderFlow
    Then the generated Gherkin contains a scenario tagged "@happy-path"
    And that scenario covers a valid order with all integrations succeeding

  @db
  Scenario: DB failure scenarios are generated for every JPA integration
    When generateFeatures is called with a flow touching PostgreSQL
    Then the Gherkin contains a scenario for "record not found" resulting in 404
    And the Gherkin contains a scenario for "optimistic lock conflict"
    And the Gherkin contains a scenario for "unique constraint violation"
    And the Gherkin contains a scenario for "transaction rollback on downstream failure"

  @kafka
  Scenario: Kafka failure scenarios are generated for every producer integration
    When generateFeatures is called with a flow that produces to Kafka
    Then the Gherkin contains a scenario for "Kafka broker unavailable"
    And the Gherkin contains a scenario for "producer timeout"
    And the Gherkin contains a scenario for "duplicate message idempotency"

  @cache
  Scenario: Cache scenarios are generated for every cache integration
    When generateFeatures is called with a flow that uses Redis cache
    Then the Gherkin contains a scenario for "cache miss — DB fallback succeeds"
    And the Gherkin contains a scenario for "cache miss — DB also fails"
    And the Gherkin contains a scenario for "stale cache during concurrent update"

  @http
  Scenario: HTTP failure scenarios are generated for every outbound HTTP integration
    When generateFeatures is called with a flow calling Stripe
    Then the Gherkin contains a scenario for HTTP 400 from Stripe
    And the Gherkin contains a scenario for HTTP 401 from Stripe
    And the Gherkin contains a scenario for HTTP 429 rate limit from Stripe
    And the Gherkin contains a scenario for HTTP 500 from Stripe
    And the Gherkin contains a scenario for "connection timeout" from Stripe
    And the Gherkin contains a scenario for "payment declined by Stripe"

  @auth
  Scenario: Auth scenarios are always included regardless of integration type
    When generateFeatures is called for any flow
    Then the Gherkin contains a scenario for "unauthenticated request returns 401"
    And the Gherkin contains a scenario for "insufficient role returns 403"
    And the Gherkin contains a scenario for "expired JWT token"

  @edge-case
  Scenario: Boundary value scenarios cover empty collections and zero amounts
    When generateFeatures is called for PlaceOrderFlow
    Then the Gherkin contains a Scenario Outline with Examples table covering:
      | condition                    |
      | empty line-item list         |
      | zero-quantity line item      |
      | negative price               |
      | max string length (255 chars)|

  @concurrency
  Scenario: Concurrency scenarios are generated for flows with DB writes
    When generateFeatures is called for a flow with JPA WRITE integration
    Then the Gherkin contains a scenario for concurrent updates to the same resource
    And the Gherkin contains a scenario for idempotency key reuse

  # ─── Structure Validation ──────────────────────────────────────────────────

  @edge-case
  Scenario: Each generated feature file has a valid Gherkin structure
    When generateFeatures is called
    Then every feature starts with the "Feature:" keyword
    And every scenario has a unique title within its feature
    And no scenario is missing Given/When/Then steps
    And all Examples tables have headers matching the step placeholders

  @edge-case
  Scenario: Tags are applied consistently
    When generateFeatures is called
    Then "@happy-path" scenarios do not have failure tags like "@db" or "@kafka"
    And all failure scenarios carry exactly one infrastructure tag

  # ─── Edge Cases in Input ───────────────────────────────────────────────────

  @edge-case
  Scenario: Flow with no integration points still generates happy path and auth scenarios
    Given a business flow with zero integration points
    When generateFeatures is called
    Then at least a "@happy-path" and "@auth" scenario are generated
    And no "@db", "@kafka", "@s3", "@cache", "@http" scenarios are generated

  @edge-case
  Scenario: Flow with all integration types generates all scenario categories
    Given a business flow with DB, KAFKA, S3, CACHE, HTTP, and THIRD_PARTY integrations
    When generateFeatures is called
    Then scenarios tagged with each of @db @kafka @s3 @cache @http @third-party are present

  @edge-case
  Scenario: Empty flows list returns empty feature list without error
    Given an empty list of business flows
    When generateFeatures is called
    Then an empty list is returned
    And no call to Claude is made

  # ─── Claude Interaction ────────────────────────────────────────────────────

  @third-party
  Scenario: Claude returns malformed response — agent retries with clarification prompt
    Given Claude returns a response not starting with "Feature:" on the first call
    When generateFeatures is invoked
    Then a second clarification prompt is sent to Claude
    And if the second response is valid Gherkin, it is returned

  @third-party
  Scenario: Claude returns markdown-fenced Gherkin — fences are stripped
    Given Claude returns "```gherkin\nFeature: ...\n```"
    When generateFeatures processes the response
    Then the stored feature text starts with "Feature:" and not "```"
