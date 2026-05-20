package com.repoinsight.integration;

import com.repoinsight.service.AnalysisJob;
import com.repoinsight.service.AnalysisJobRepository;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * Full-stack integration test: real Spring context, H2 DB, mocked GitHub + Copilot endpoints.
 * Verifies the complete analysis pipeline end-to-end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RepoDocIntegrationTest {

    static MockWebServer mockGitHub = new MockWebServer();
    static MockWebServer mockCopilotToken = new MockWebServer();
    static MockWebServer mockCopilotCompletions = new MockWebServer();

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) throws IOException {
        mockGitHub.start();
        mockCopilotToken.start();
        mockCopilotCompletions.start();
        registry.add("github.api-base-url", () -> "http://localhost:" + mockGitHub.getPort());
        registry.add("github.copilot.token-exchange-url",
                () -> "http://localhost:" + mockCopilotToken.getPort());
        registry.add("github.copilot.completions-url",
                () -> "http://localhost:" + mockCopilotCompletions.getPort());
    }

    @AfterAll
    static void tearDown() throws IOException {
        mockGitHub.shutdown();
        mockCopilotToken.shutdown();
        mockCopilotCompletions.shutdown();
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AnalysisJobRepository jobRepository;

    @Test
    @DisplayName("End-to-end: submit → poll → all artifacts returned")
    void fullPipeline_happyPath() {
        stubCopilotToken();
        stubGitHub();
        stubCopilotCompletion("{\"flows\":[{\"name\":\"CreateOrder\",\"trigger\":\"POST /orders\",\"description\":\"Places order\",\"steps\":[\"validate\"],\"invariants\":[\"cart not empty\"],\"sideEffects\":[\"event emitted\"]}]}");
        stubCopilotCompletion("{\"trees\":[{\"className\":\"OrderController\",\"methodSignature\":\"create()\",\"layer\":\"CONTROLLER\",\"integrationKind\":\"NONE\",\"depth\":0,\"children\":[]}]}");
        stubCopilotCompletion("{\"integrations\":[{\"category\":\"DB\",\"name\":\"PostgreSQL\",\"classRef\":\"OrderRepo\",\"methodRef\":\"save\",\"detectionPattern\":\"@Repository\",\"direction\":\"WRITE\"}]}");
        stubCopilotCompletion("Feature: CreateOrder\n\n  Scenario: Happy path\n    Given ...\n    When ...\n    Then ...");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                { "repoUrl": "https://github.com/example/orders", "branch": "main" }
                """;
        ResponseEntity<Map> submitResponse = restTemplate.postForEntity(
                "/api/v1/analyse", new HttpEntity<>(body, headers), Map.class);

        assertThat(submitResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String jobId = (String) submitResponse.getBody().get("jobId");
        assertThat(jobId).isNotBlank();

        await().atMost(30, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(() -> {
            ResponseEntity<Map> poll = restTemplate.getForEntity("/api/v1/jobs/" + jobId, Map.class);
            return "COMPLETED".equals(poll.getBody().get("status"))
                    || "FAILED".equals(poll.getBody().get("status"));
        });

        AnalysisJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(AnalysisJob.JobStatus.COMPLETED);
        assertThat(job.getBusinessLogicDoc()).isNotBlank();
        assertThat(job.getMethodHierarchyDoc()).isNotBlank();
        assertThat(job.getIntegrationsDoc()).isNotBlank();
        assertThat(job.getGherkinBundle()).isNotBlank();
    }

    @Test
    @DisplayName("GitHub 404 → job fails with descriptive error")
    void githubNotFound_jobFails() {
        stubCopilotToken();
        mockGitHub.enqueue(new MockResponse().setResponseCode(404).setBody("{\"message\":\"Not Found\"}"));

        String body = """
                { "repoUrl": "https://github.com/example/missing-repo", "branch": "main" }
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> submitResponse = restTemplate.postForEntity(
                "/api/v1/analyse", new HttpEntity<>(body, headers), Map.class);
        String jobId = (String) submitResponse.getBody().get("jobId");

        await().atMost(15, TimeUnit.SECONDS).until(() -> {
            AnalysisJob job = jobRepository.findById(jobId).orElseThrow();
            return job.getStatus() == AnalysisJob.JobStatus.FAILED
                    || job.getStatus() == AnalysisJob.JobStatus.COMPLETED;
        });

        AnalysisJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(AnalysisJob.JobStatus.FAILED);
        assertThat(job.getErrorMessage()).isNotBlank();
    }

    // ── Stubs ────────────────────────────────────────────────────────────────

    private void stubCopilotToken() {
        mockCopilotToken.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"token\":\"test-session-token\",\"expires_at\":%d}"
                        .formatted(System.currentTimeMillis() / 1000 + 3600)));
    }

    private void stubGitHub() {
        String treeJson = """
                {"tree":[
                  {"path":"src/main/java/OrderService.java","type":"blob","sha":"sha1","size":200}
                ],"truncated":false}
                """;
        mockGitHub.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(treeJson));

        String encoded = Base64.getEncoder().encodeToString(
                "public class OrderService { void createOrder() {} }".getBytes());
        mockGitHub.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"content\":\"%s\"}".formatted(encoded)));
    }

    private void stubCopilotCompletion(String text) {
        String escaped = text.replace("\"", "\\\"").replace("\n", "\\n");
        String response = """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "model": "gpt-4o",
                  "choices": [{
                    "index": 0,
                    "message": { "role": "assistant", "content": "%s" },
                    "finish_reason": "stop"
                  }],
                  "usage": { "prompt_tokens": 100, "completion_tokens": 200, "total_tokens": 300 }
                }
                """.formatted(escaped);
        mockCopilotCompletions.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(response));
    }
}
