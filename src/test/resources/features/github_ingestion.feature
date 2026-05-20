@github-ingestion
Feature: GitHub Repository Ingestion

  As the analysis engine
  I want to reliably fetch Java source files from GitHub
  So that agents receive complete, clean, secret-free content

  Background:
    Given the GitHubRepoFetcher is configured with base URL pointing to a mock server

  # ─── Happy Path ────────────────────────────────────────────────────────────

  @happy-path
  Scenario: Fetch all Java files from a well-structured Spring Boot project
    Given the mock GitHub API returns a tree with 15 Java files and 3 non-Java files
    When GitHubRepoFetcher.fetchJavaFiles("example", "spring-orders", "main") is called
    Then 15 GitHubFile objects are returned
    And no GitHubFile has a path ending in ".xml", ".yml", or ".properties"

  @happy-path
  Scenario: Private repository accessed with a valid token
    Given the GitHub token "ghp_validToken123" is configured
    And the mock server expects Authorization header "Bearer ghp_validToken123"
    When GitHubRepoFetcher.fetchJavaFiles is called
    Then the request includes the Authorization header
    And files are returned successfully

  # ─── Filtering ─────────────────────────────────────────────────────────────

  @edge-case
  Scenario: Test sources are excluded by default
    Given the repository tree contains:
      | path                                        | type |
      | src/main/java/com/example/OrderService.java | blob |
      | src/test/java/com/example/OrderServiceTest.java | blob |
      | src/main/java/com/example/config/AppConfig.java | blob |
    When GitHubRepoFetcher.fetchJavaFiles is called with default options
    Then only 2 files are returned
    And "OrderServiceTest.java" is not in the result

  @edge-case
  Scenario: Generated and build output directories are excluded
    Given the repository tree contains files under "target/", "build/", "generated-sources/"
    When GitHubRepoFetcher.fetchJavaFiles is called
    Then no files from excluded directories are returned

  @edge-case
  Scenario: Files exceeding the 100 KB size limit are skipped
    Given the repository contains a file "LegacyMonolith.java" of 250 KB
    When GitHubRepoFetcher.fetchJavaFiles is called
    Then "LegacyMonolith.java" is not in the result
    And no error is thrown

  @edge-case
  Scenario: Repository with more than 500 Java files is capped
    Given the repository tree contains 750 Java blobs
    When GitHubRepoFetcher.fetchJavaFiles is called
    Then exactly 500 GitHubFile objects are returned

  # ─── Secret Scrubbing ──────────────────────────────────────────────────────

  @security
  Scenario Outline: Secrets in source files are redacted before returning
    Given a file contains the text "<secretText>"
    When GitHubRepoFetcher fetches that file
    Then the returned GitHubFile.content contains "[REDACTED]"
    And does not contain "<secretText>"

    Examples:
      | secretText                                  |
      | password = "myS3cretP@ss"                   |
      | token = "ghp_abcdefghij1234567890ABCDE"     |
      | apiKey = "sk-live-deadbeef1234567890"        |
      | Authorization: Bearer eyJhbGciOiJIUzI1Ni    |

  # ─── Retry Behaviour ───────────────────────────────────────────────────────

  @http @edge-case
  Scenario: Transient 500 errors on blob fetch are retried twice then succeed
    Given the mock server returns 500 on the first 2 requests for a specific blob
    And then returns 200 on the 3rd request
    When GitHubRepoFetcher fetches that file
    Then the file content is returned successfully
    And 3 total HTTP calls were made

  @http @edge-case
  Scenario: Blob fetch fails after all retries — file silently skipped
    Given the mock server returns 500 for all attempts on "BrokenFile.java"
    When GitHubRepoFetcher fetches the repository
    Then "BrokenFile.java" is absent from the result
    And the other files in the repository are returned normally
    And no exception is propagated to the caller

  @http @edge-case
  Scenario: Empty repository returns an empty list without error
    Given the mock GitHub API returns a tree with 0 files
    When GitHubRepoFetcher.fetchJavaFiles is called
    Then an empty list is returned

  # ─── URL Parsing ───────────────────────────────────────────────────────────

  @edge-case
  Scenario Outline: RepoCoordinates.parse correctly extracts owner and repo
    When RepoCoordinates.parse("<url>", "main") is called
    Then owner is "<owner>" and repo is "<repo>"

    Examples:
      | url                                         | owner   | repo         |
      | https://github.com/spring-projects/spring-boot | spring-projects | spring-boot |
      | https://github.com/acme/my-service          | acme    | my-service   |
      | https://github.com/acme/my-service/         | acme    | my-service   |

  @edge-case
  Scenario: Malformed GitHub URL throws IllegalArgumentException
    When RepoCoordinates.parse("https://github.com/only-one-segment", "main") is called
    Then an IllegalArgumentException is thrown with message containing "Invalid GitHub URL"
