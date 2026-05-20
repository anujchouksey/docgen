@api
Feature: Repository Analysis API

  As a developer
  I want to submit a GitHub repository for analysis
  So that I receive business documentation and Gherkin test suites automatically

  Background:
    Given the RepoDocGen service is running
    And a valid GitHub repository exists at "https://github.com/example/spring-orders"

  # ─── Happy Path ────────────────────────────────────────────────────────────

  @happy-path
  Scenario: Successful analysis of a Spring Boot order-management repository
    Given the repository "spring-orders" contains 42 Java source files
    And the Anthropic API is available
    When I POST to "/api/v1/analyse" with body:
      """
      {
        "repoUrl": "https://github.com/example/spring-orders",
        "branch": "main",
        "options": { "maxDepth": 5 }
      }
      """
    Then the response status is 202
    And the response contains "jobId"
    And the response contains "pollUrl"
    When I poll the "pollUrl" until status is "COMPLETED" within 120 seconds
    Then the job has "filesAnalysed" greater than 0
    And the artifact "business-logic" is non-empty Markdown
    And the artifact "method-hierarchy" contains a call tree with depth at least 2
    And the artifact "integrations" lists at least one integration point
    And the artifact "gherkin" contains at least one Gherkin feature

  @happy-path
  Scenario: Analysis on a non-default branch
    When I POST to "/api/v1/analyse" with body:
      """
      {
        "repoUrl": "https://github.com/example/spring-orders",
        "branch": "develop"
      }
      """
    Then the response status is 202
    And the job eventually completes with status "COMPLETED"

  # ─── Input Validation ──────────────────────────────────────────────────────

  @edge-case
  Scenario Outline: Invalid request inputs are rejected
    When I POST to "/api/v1/analyse" with body "<payload>"
    Then the response status is 400
    And the response error message contains "<expectedError>"

    Examples:
      | payload                                                        | expectedError               |
      | { "repoUrl": "", "branch": "main" }                            | repoUrl must not be blank   |
      | { "repoUrl": "https://gitlab.com/org/repo", "branch": "main" }| Must be a valid GitHub URL  |
      | { "branch": "main" }                                           | repoUrl must not be blank   |
      | { "repoUrl": "https://github.com/org/repo" }                   | branch must not be blank    |

  @edge-case
  Scenario: Requesting a non-existent job ID returns 404
    When I GET "/api/v1/jobs/non-existent-id-00000000"
    Then the response status is 404

  @edge-case
  Scenario: Fetching artifacts for a still-running job returns 409
    Given a job exists with status "RUNNING"
    When I GET "/api/v1/jobs/{jobId}/artifacts/business-logic"
    Then the response status is 409

  # ─── GitHub Integration Failures ───────────────────────────────────────────

  @http @edge-case
  Scenario: GitHub repository does not exist
    Given the GitHub API returns 404 for "https://github.com/example/does-not-exist"
    When I POST to "/api/v1/analyse" with body:
      """
      {
        "repoUrl": "https://github.com/example/does-not-exist",
        "branch": "main"
      }
      """
    Then the job eventually completes with status "FAILED"
    And the job error message contains "Could not retrieve repo tree"

  @http @edge-case
  Scenario: GitHub API rate limit exceeded
    Given the GitHub API returns 403 with "rate limit exceeded" for all requests
    When I POST to "/api/v1/analyse" for any repository
    Then the job eventually completes with status "FAILED"
    And the job error message contains "rate limit"

  @http @edge-case
  Scenario: GitHub API returns 500 — retried 3 times then fails
    Given the GitHub API returns 500 for the first 2 requests then succeeds
    When I POST to "/api/v1/analyse" for a valid repository
    Then the job eventually completes with status "COMPLETED"

  @http @edge-case
  Scenario: GitHub API timeout
    Given the GitHub API response is delayed by 35 seconds
    When I POST to "/api/v1/analyse" for a valid repository
    Then the job eventually completes with status "FAILED"
    And the job error message contains "timeout"

  # ─── Claude / Anthropic API Failures ───────────────────────────────────────

  @third-party @edge-case
  Scenario: Anthropic API key is invalid
    Given the Anthropic API returns 401 "invalid_api_key"
    When I POST to "/api/v1/analyse" for a valid repository
    Then the job eventually completes with status "FAILED"
    And the job error message contains "401"

  @third-party @edge-case
  Scenario: Anthropic API rate limited (429)
    Given the Anthropic API returns 429 on the second agent call
    When I POST to "/api/v1/analyse" for a valid repository
    Then the job eventually completes with status "FAILED"
    And the job error message contains "429"

  # ─── Large Repository ──────────────────────────────────────────────────────

  @edge-case
  Scenario: Repository exceeds 500 file limit
    Given the repository contains 750 Java source files
    When I POST to "/api/v1/analyse" for that repository
    Then only the first 500 files are analysed
    And the job eventually completes with status "COMPLETED"

  @edge-case
  Scenario: Repository tree is truncated by GitHub (very large repo)
    Given the GitHub API returns a truncated tree response
    When I POST to "/api/v1/analyse" for that repository
    Then the job completes with status "COMPLETED"
    And the warning log contains "tree is truncated"

  # ─── Secret Scrubbing ──────────────────────────────────────────────────────

  @security
  Scenario: Source files containing hardcoded secrets are scrubbed before sending to Claude
    Given a repository contains a file with "apiKey = 'sk-live-abcdef1234567890'"
    When that file is fetched and prepared for analysis
    Then the content sent to Claude contains "[REDACTED]" in place of the secret
    And the original secret value is not present in any artifact

  # ─── DB Persistence ────────────────────────────────────────────────────────

  @db
  Scenario: Completed job results are persisted and retrievable after restart
    Given a job has completed with status "COMPLETED"
    When the service restarts
    And I GET "/api/v1/jobs/{jobId}"
    Then the response status is 200
    And all artifact URLs are still valid

  @db
  Scenario: Concurrent job submissions for the same repo
    When I submit 3 analysis jobs for the same repository simultaneously
    Then all 3 jobs are created with unique job IDs
    And all 3 jobs eventually complete
