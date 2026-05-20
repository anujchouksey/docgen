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
 * Full-stack integration test: real Spring context, H2 DB, mocked GitHub + Anthropic.
 * Verifies the complete analysis pipeline end-to-end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RepoDocIntegrationTest {

    static MockWebServer mockGitHub = new MockWebServer();
    static MockWebServer mockAnthropic = new MockWebServer();

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) throws IOException {
        mockGitHub.start();
        mockAnthropic.start();
        registry.add("github.api-base-url", () -> "http://localhost:" + mockGitHub.getPort());
        registry.add("anthropic.api-key", () -> "test-key");
        // Override Anthropic base URL via env; SDK picks it up
        registry.add("ANTHROPIC_BASE_URL", () -> "http://localhost:" + mockAnthropic.getPort());
    }

    @AfterAll
    static void tearDown() throws IOException {
        mockGitHub.shutdown();
        mockAnthropic.shutdown();
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AnalysisJobRepository jobRepository;

    @Test
    @DisplayName("End-to-end: submit → poll → all artifacts returned")
    void fullPipeline_happyPath() {
        stubGitHub();
        stubAnthropic();

        // Submit analysis
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

        // Poll until completed
        await().atMost(30, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(() -> {
            ResponseEntity<Map> poll = restTemplate.getForEntity("/api/v1/jobs/" + jobId, Map.class);
            return "COMPLETED".equals(poll.getBody().get("status"))
                    || "FAILED".equals(poll.getBody().get("status"));
        });

        // Verify final state
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

    private void stubAnthropic() {
        String flows = anthropicResponse("{\"flows\":[{\"name\":\"CreateOrder\",\"trigger\":\"POST /orders\",\"description\":\"Places order\",\"steps\":[\"validate\"],\"invariants\":[\"cart not empty\"],\"sideEffects\":[\"event emitted\"]}]}");
        String trees = anthropicResponse("{\"trees\":[{\"className\":\"OrderController\",\"methodSignature\":\"create()\",\"layer\":\"CONTROLLER\",\"integrationKind\":\"NONE\",\"depth\":0,\"children\":[]}]}");
        String integrations = anthropicResponse("{\"integrations\":[{\"category\":\"DB\",\"name\":\"PostgreSQL\",\"classRef\":\"OrderRepo\",\"methodRef\":\"save\",\"detectionPattern\":\"@Repository\",\"direction\":\"WRITE\"}]}");
        String gherkin = anthropicResponse("Feature: CreateOrder\n\n  Scenario: Happy path\n    Given ...\n    When ...\n    Then ...");

        mockAnthropic.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(flows));
        mockAnthropic.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(trees));
        mockAnthropic.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(integrations));
        mockAnthropic.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(gherkin));
    }

    private String anthropicResponse(String text) {
        return """
                {
                  "id": "msg_test",
                  "type": "message",
                  "role": "assistant",
                  "content": [{"type": "text", "text": "%s"}],
                  "model": "claude-sonnet-4-6",
                  "stop_reason": "end_turn",
                  "usage": {"input_tokens": 100, "output_tokens": 200}
                }
                """.formatted(text.replace("\"", "\\\"").replace("\n", "\\n"));
    }
}
