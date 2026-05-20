@coverage-comparison
Feature: Dev vs QA Coverage Comparison

  As a QA lead
  I want to compare a dev repository against a QA test repository
  So that I receive a precise, AI-generated report of what is covered, partially covered,
  missed, and not needed — with suggested Gherkin for every gap

  Background:
    Given the CoverageComparisonService is running
    And a dev repository exists at "https://github.com/example/order-service" on branch "main"
    And a QA repository exists at "https://github.com/example/order-service-qa" on branch "main"

  # ─── API Contract ──────────────────────────────────────────────────────────

  @happy-path
  Scenario: Valid comparison request returns 202 with jobId
    When I POST to "/api/v1/coverage/compare" with body:
      """
      {
        "devRepoUrl": "https://github.com/example/order-service",
        "devBranch": "main",
        "qaRepoUrl":  "https://github.com/example/order-service-qa",
        "qaBranch":   "main",
        "layerFocus": "ALL"
      }
      """
    Then the response status is 202
    And the response body contains "jobId"
    And the response body contains "pollUrl"

  @happy-path
  Scenario: Completed job returns a report via GET /report
    Given a comparison job has completed with status "COMPLETED"
    When I GET "/api/v1/coverage/jobs/{jobId}/report"
    Then the response status is 200
    And the report contains a "summary" object with field "coverageScore"
    And the report contains a "classes" array with at least one entry
    And the report contains an "executiveSummary" string

  @edge-case
  Scenario: Fetching report for a still-running job returns 500
    Given a comparison job has status "RUNNING"
    When I GET "/api/v1/coverage/jobs/{jobId}/report"
    Then the response status is 500
    And the error message contains "not completed"

  @edge-case
  Scenario Outline: Invalid request inputs return 400
    When I POST to "/api/v1/coverage/compare" with payload "<payload>"
    Then the response status is 400
    And the error contains "<field>"

    Examples:
      | payload                                                                                            | field       |
      | { "devRepoUrl": "", "devBranch": "main", "qaRepoUrl": "https://github.com/o/r", "qaBranch": "main" } | devRepoUrl  |
      | { "devRepoUrl": "https://github.com/o/r", "devBranch": "main", "qaRepoUrl": "", "qaBranch": "main" } | qaRepoUrl   |
      | { "devRepoUrl": "https://gitlab.com/o/r", "devBranch": "main", "qaRepoUrl": "https://github.com/o/r", "qaBranch": "main" } | devRepoUrl  |

  # ─── Coverage Status Accuracy ──────────────────────────────────────────────

  @happy-path
  Scenario: Service class with full happy-path AND failure tests is marked COVERED
    Given the dev repo contains "OrderService.java" with methods:
      | createOrder | cancelOrder | getOrderById |
    And the QA repo contains "OrderServiceTest.java" that tests all three methods with:
      | happy path | record not found | DB constraint violation | concurrent update |
    When the comparison completes
    Then the ClassCoverageReport for "OrderService" has status "COVERED"
    And "coveredMethods" contains all three method names
    And "missedMethods" is empty
    And "missingScenarios" is empty

  @happy-path
  Scenario: Service class tested only on happy path is marked PARTIAL
    Given the dev repo contains "PaymentService.java" with method "chargeCard"
    And the QA repo contains "PaymentServiceTest.java" testing only the happy path of "chargeCard"
    And there are no tests for: payment declined, Stripe 500, timeout, card expired
    When the comparison completes
    Then the ClassCoverageReport for "PaymentService" has status "PARTIAL"
    And "coveredMethods" contains "chargeCard"
    And "missingScenarios" contains at least:
      | payment declined by Stripe          |
      | Stripe API 500 server error         |
      | request timeout to payment gateway  |

  @happy-path
  Scenario: Service class with no QA file reference is marked MISSED
    Given the dev repo contains "NotificationService.java"
    And the QA repo contains no file that references "NotificationService" or its package
    When the comparison completes
    Then the ClassCoverageReport for "NotificationService" has status "MISSED"
    And "relevantQaFiles" is empty
    And "suggestedGherkin" is non-empty and starts with a Gherkin scenario keyword

  @happy-path
  Scenario: DTO class with only getters and setters is marked NOT_NEEDED
    Given the dev repo contains "OrderDto.java" with only:
      | @Getter @Setter fields | no-arg constructor | all-args constructor |
    When the comparison completes
    Then the ClassCoverageReport for "OrderDto" has status "NOT_NEEDED"
    And "explanation" mentions "boilerplate" or "no business logic"

  # ─── Layer-Specific Coverage Scenarios ────────────────────────────────────

  @happy-path
  Scenario: Controller method missing auth test is marked PARTIAL
    Given the dev repo contains "OrderController.java" with method "createOrder" secured by @PreAuthorize
    And the QA repo tests the happy path but has no test for:
      | unauthenticated request | user with wrong role | expired JWT |
    When the comparison completes
    Then the ClassCoverageReport for "OrderController" has status "PARTIAL"
    And "missingScenarios" contains "unauthenticated" or "role"

  @happy-path
  Scenario: Repository method missing DB error test is marked PARTIAL
    Given the dev repo contains "OrderRepository.java" using Spring Data JPA
    And the QA repo tests happy read/write paths but has no test for:
      | DataIntegrityViolationException | optimistic locking | empty result |
    When the comparison completes
    Then the ClassCoverageReport for "OrderRepository" has status "PARTIAL"
    And "missingScenarios" includes a DB-related scenario

  # ─── Integration Point Coverage Scenarios ─────────────────────────────────

  @kafka
  Scenario: Kafka producer method without broker-failure test is PARTIAL
    Given the dev repo has "OrderEventPublisher.java" calling "kafkaTemplate.send()"
    And the QA repo has no scenario for "Kafka broker unavailable" or "producer timeout"
    When the comparison completes
    Then "OrderEventPublisher" is PARTIAL
    And "missingScenarios" mentions Kafka failure

  @kafka
  Scenario: Kafka consumer missing poison-pill test is PARTIAL
    Given the dev repo has "OrderConsumer.java" annotated with "@KafkaListener"
    And the QA repo tests happy-path consumption but not malformed payloads or DLQ routing
    When the comparison completes
    Then "OrderConsumer" is PARTIAL
    And "missingScenarios" contains "poison pill" or "DLQ"

  @s3
  Scenario: S3 upload method missing failure test is PARTIAL
    Given the dev repo has "DocumentUploadService.java" using AmazonS3.putObject()
    And the QA repo has no test for "S3 upload failure" or "access denied"
    When the comparison completes
    Then "DocumentUploadService" is PARTIAL
    And "missingScenarios" includes an S3 failure scenario

  @cache
  Scenario: Service with cache but no cache-miss test is PARTIAL
    Given the dev repo has "ProductService.java" with "@Cacheable" on "getProduct"
    And the QA repo tests only the case where the cache is warm
    When the comparison completes
    Then "ProductService" is PARTIAL
    And "missingScenarios" contains "cache miss" or "cache eviction"

  # ─── QA File Type Coverage ─────────────────────────────────────────────────

  @happy-path
  Scenario Outline: QA fetcher recognises all supported test file patterns
    Given the QA repo contains a file named "<filename>"
    When QARepoFetcher fetches the QA repo
    Then "<filename>" is included in the fetched test files

    Examples:
      | filename                        |
      | OrderServiceTest.java           |
      | OrderServiceIT.java             |
      | OrderServiceSpec.java           |
      | order_placement.feature         |
      | OrderSteps.java                 |
      | OrderStepDefs.java              |
      | OrderStepDefinitions.java       |
      | OrderServiceE2ETest.java        |

  @happy-path
  Scenario: Non-test Java files in QA repo are excluded from analysis context
    Given the QA repo contains "TestConfig.java" (no test suffix) alongside test files
    When QARepoFetcher fetches the QA repo
    Then "TestConfig.java" is not in the fetched test files

  # ─── Suggested Gherkin Quality ─────────────────────────────────────────────

  @happy-path
  Scenario: Suggested Gherkin for missed class contains all required categories
    Given a MISSED class "InventoryService" with Kafka and DB integrations
    When the comparison completes
    Then the "suggestedGherkin" for "InventoryService" contains:
      | @happy-path scenario                |
      | @db scenario for record not found   |
      | @kafka scenario for broker failure  |

  @happy-path
  Scenario: Suggested Gherkin uses realistic data, not placeholder values
    When the comparison produces suggestedGherkin for any class
    Then no suggestedGherkin field contains "foo", "bar", "test123", or "example_value"

  # ─── Executive Summary ─────────────────────────────────────────────────────

  @happy-path
  Scenario: Executive summary references coverage score and worst layer
    Given a comparison has completed
    When I inspect the "executiveSummary" field
    Then it mentions the numeric coverage score (e.g. "42%")
    And it identifies the layer with the most missed classes
    And it is written as flowing prose (no bullet points or numbered lists)
    And it is between 2 and 6 sentences long

  # ─── Large Repo / Performance ──────────────────────────────────────────────

  @edge-case
  Scenario: Dev repo with 60 classes is processed in 3 batches of 20
    Given the dev repo contains 60 Java source files
    When the comparison runs
    Then the CoverageComparisonAgent is called exactly 3 times (one per batch)
    And all 60 classes appear in the final report

  @edge-case
  Scenario: Empty QA repo produces all classes as MISSED
    Given the QA repo contains no test files
    When the comparison completes
    Then every non-NOT_NEEDED class in the dev repo has status "MISSED"
    And the coverageScore is 0.0

  @edge-case
  Scenario: Empty dev repo produces an empty classes list and score of 0
    Given the dev repo contains no Java files
    When the comparison completes
    Then the "classes" array is empty
    And "coverageScore" is 0.0
    And no error is thrown

  # ─── Report Format Options ─────────────────────────────────────────────────

  @happy-path
  Scenario: GAPS_ONLY format excludes COVERED classes from the report
    When I POST a comparison with reportFormat "GAPS_ONLY"
    And the comparison completes
    Then the "classes" array contains no entry with status "COVERED"
    And PARTIAL, MISSED classes are present

  @happy-path
  Scenario: EXECUTIVE format returns only summary and executiveSummary
    When I POST a comparison with reportFormat "EXECUTIVE"
    And the comparison completes
    Then the response contains "summary" and "executiveSummary"
    And the "classes" array is absent or empty
