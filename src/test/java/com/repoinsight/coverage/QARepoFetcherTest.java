package com.repoinsight.coverage;

import com.repoinsight.coverage.service.QARepoFetcher;
import com.repoinsight.github.model.GitHubFile;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;

import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class QARepoFetcherTest {

    private MockWebServer mockServer;
    private QARepoFetcher fetcher;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        WebClient client = WebClient.builder().baseUrl(mockServer.url("/").toString()).build();
        fetcher = new QARepoFetcher(client);
        ReflectionTestUtils.setField(fetcher, "maxFileSizeBytes", 102400L);
        ReflectionTestUtils.setField(fetcher, "maxFiles", 500);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @ParameterizedTest
    @DisplayName("Recognised test file patterns are fetched")
    @ValueSource(strings = {
            "OrderServiceTest.java", "OrderServiceIT.java", "OrderServiceSpec.java",
            "OrderSteps.java", "OrderStepDefs.java", "OrderStepDefinitions.java",
            "OrderServiceE2ETest.java", "order_placement.feature"
    })
    void fetchTestFiles_recognisedPatterns_included(String filename) {
        String sha = "abc123";
        mockServer.enqueue(treeResponse("""
                {"tree":[{"path":"src/test/%s","type":"blob","sha":"%s","size":200}],"truncated":false}
                """.formatted(filename, sha)));
        mockServer.enqueue(blobResponse("// test content"));

        List<GitHubFile> result = fetcher.fetchTestFiles("org", "repo", "main");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPath()).endsWith(filename);
    }

    @ParameterizedTest
    @DisplayName("Non-test Java files are excluded")
    @ValueSource(strings = {
            "OrderService.java", "TestConfig.java", "BaseHelper.java", "OrderMapper.java"
    })
    void fetchTestFiles_nonTestFiles_excluded(String filename) {
        mockServer.enqueue(treeResponse("""
                {"tree":[{"path":"src/%s","type":"blob","sha":"x1","size":100}],"truncated":false}
                """.formatted(filename)));

        List<GitHubFile> result = fetcher.fetchTestFiles("org", "repo", "main");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Mixed repo: only test files are returned")
    void fetchTestFiles_mixedRepo_onlyTestsReturned() {
        mockServer.enqueue(treeResponse("""
                {"tree":[
                  {"path":"src/main/java/OrderService.java","type":"blob","sha":"a1","size":100},
                  {"path":"src/test/java/OrderServiceTest.java","type":"blob","sha":"a2","size":100},
                  {"path":"src/test/resources/order.feature","type":"blob","sha":"a3","size":80}
                ],"truncated":false}
                """));
        mockServer.enqueue(blobResponse("@Test void test() {}"));
        mockServer.enqueue(blobResponse("Feature: Order placement"));

        List<GitHubFile> result = fetcher.fetchTestFiles("org", "repo", "main");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(GitHubFile::getPath)
                .doesNotContain("src/main/java/OrderService.java");
    }

    @Test
    @DisplayName("Empty QA repo returns empty list without exception")
    void fetchTestFiles_emptyRepo_returnsEmpty() {
        mockServer.enqueue(treeResponse("""
                {"tree":[],"truncated":false}
                """));

        List<GitHubFile> result = fetcher.fetchTestFiles("org", "empty-qa", "main");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("File exceeding size limit is excluded")
    void fetchTestFiles_largeFile_excluded() {
        mockServer.enqueue(treeResponse("""
                {"tree":[
                  {"path":"HugeTest.java","type":"blob","sha":"h1","size":200001},
                  {"path":"SmallTest.java","type":"blob","sha":"h2","size":100}
                ],"truncated":false}
                """));
        mockServer.enqueue(blobResponse("@Test void ok() {}"));

        List<GitHubFile> result = fetcher.fetchTestFiles("org", "repo", "main");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPath()).isEqualTo("SmallTest.java");
    }

    @Test
    @DisplayName("Failed blob fetch is silently skipped; other files returned")
    void fetchTestFiles_blobFailure_otherFilesStillReturned() {
        mockServer.enqueue(treeResponse("""
                {"tree":[
                  {"path":"GoodTest.java","type":"blob","sha":"g1","size":100},
                  {"path":"BadTest.java","type":"blob","sha":"b1","size":100}
                ],"truncated":false}
                """));
        mockServer.enqueue(blobResponse("@Test void good() {}"));
        // Simulate failure for second file (3 attempts: initial + 2 retries)
        mockServer.enqueue(new MockResponse().setResponseCode(500));
        mockServer.enqueue(new MockResponse().setResponseCode(500));
        mockServer.enqueue(new MockResponse().setResponseCode(500));

        List<GitHubFile> result = fetcher.fetchTestFiles("org", "repo", "main");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPath()).isEqualTo("GoodTest.java");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private MockResponse treeResponse(String json) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(json);
    }

    private MockResponse blobResponse(String content) {
        String encoded = Base64.getEncoder().encodeToString(content.getBytes());
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"content\":\"%s\"}".formatted(encoded));
    }
}
