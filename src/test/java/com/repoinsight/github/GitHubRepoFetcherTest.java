package com.repoinsight.github;

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

class GitHubRepoFetcherTest {

    private MockWebServer mockServer;
    private GitHubRepoFetcher fetcher;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        WebClient client = WebClient.builder()
                .baseUrl(mockServer.url("/").toString())
                .build();
        fetcher = new GitHubRepoFetcher(client);
        ReflectionTestUtils.setField(fetcher, "maxFileSizeBytes", 102400L);
        ReflectionTestUtils.setField(fetcher, "maxFiles", 500);
        ReflectionTestUtils.setField(fetcher, "secretPatterns",
                List.of("(?i)(password|secret|token|key|auth)\\s*=\\s*['\"][^'\"]{6,}['\"]",
                        "(?i)Bearer\\s+[A-Za-z0-9\\-._~+/]+=*"));
        ReflectionTestUtils.setField(fetcher, "excludedPathSegments",
                List.of("/test/", "/generated/", "/target/", "/build/", "/.mvn/"));
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("Fetches only .java blobs from the tree")
    void fetchJavaFiles_filtersNonJava() {
        mockServer.enqueue(treeResponse("""
            {"tree":[
              {"path":"src/main/java/App.java","type":"blob","sha":"abc1","size":100},
              {"path":"src/main/resources/app.yml","type":"blob","sha":"abc2","size":50},
              {"path":"pom.xml","type":"blob","sha":"abc3","size":200}
            ],"truncated":false}
            """));
        mockServer.enqueue(blobResponse("public class App {}"));

        List<GitHubFile> result = fetcher.fetchJavaFiles("org", "repo", "main");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPath()).isEqualTo("src/main/java/App.java");
    }

    @Test
    @DisplayName("Excludes test/, generated/, target/ paths")
    void fetchJavaFiles_excludesTestAndBuildPaths() {
        mockServer.enqueue(treeResponse("""
            {"tree":[
              {"path":"src/main/java/Svc.java","type":"blob","sha":"a1","size":100},
              {"path":"src/test/java/SvcTest.java","type":"blob","sha":"a2","size":100},
              {"path":"target/classes/Compiled.java","type":"blob","sha":"a3","size":100}
            ],"truncated":false}
            """));
        mockServer.enqueue(blobResponse("public class Svc {}"));

        List<GitHubFile> result = fetcher.fetchJavaFiles("org", "repo", "main");

        assertThat(result).hasSize(1);
        assertThat(result).extracting(GitHubFile::getPath).containsExactly("src/main/java/Svc.java");
    }

    @Test
    @DisplayName("Files exceeding 100 KB are silently skipped")
    void fetchJavaFiles_skipsOversizedFiles() {
        mockServer.enqueue(treeResponse("""
            {"tree":[
              {"path":"Small.java","type":"blob","sha":"s1","size":500},
              {"path":"Huge.java","type":"blob","sha":"s2","size":200000}
            ],"truncated":false}
            """));
        mockServer.enqueue(blobResponse("class Small {}"));

        List<GitHubFile> result = fetcher.fetchJavaFiles("org", "repo", "main");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPath()).isEqualTo("Small.java");
    }

    @Test
    @DisplayName("Empty repository returns empty list")
    void fetchJavaFiles_emptyRepo_returnsEmptyList() {
        mockServer.enqueue(treeResponse("""
            {"tree":[],"truncated":false}
            """));

        List<GitHubFile> result = fetcher.fetchJavaFiles("org", "empty-repo", "main");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Failed blob fetch is silently skipped; other files returned")
    void fetchJavaFiles_blobFetchFailure_skipsFile() {
        mockServer.enqueue(treeResponse("""
            {"tree":[
              {"path":"Good.java","type":"blob","sha":"g1","size":100},
              {"path":"Bad.java","type":"blob","sha":"b1","size":100}
            ],"truncated":false}
            """));
        mockServer.enqueue(blobResponse("class Good {}"));
        mockServer.enqueue(new MockResponse().setResponseCode(500));
        mockServer.enqueue(new MockResponse().setResponseCode(500)); // retry 1
        mockServer.enqueue(new MockResponse().setResponseCode(500)); // retry 2

        List<GitHubFile> result = fetcher.fetchJavaFiles("org", "repo", "main");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPath()).isEqualTo("Good.java");
    }

    @ParameterizedTest
    @DisplayName("Secrets are scrubbed from file content")
    @ValueSource(strings = {
        "password = \"myS3cret\"",
        "apiKey = \"sk-live-deadbeef\"",
        "token = \"ghp_sometoken123\""
    })
    void fetchJavaFiles_scrubsSecrets(String secretLine) {
        mockServer.enqueue(treeResponse("""
            {"tree":[{"path":"Config.java","type":"blob","sha":"c1","size":100}],"truncated":false}
            """));
        mockServer.enqueue(blobResponse("class Config { String x = \"%s\"; }".formatted(secretLine)));

        List<GitHubFile> result = fetcher.fetchJavaFiles("org", "repo", "main");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).contains("[REDACTED]");
        assertThat(result.get(0).getContent()).doesNotContain(secretLine);
    }

    // ── URL parsing ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("RepoCoordinates parsed from standard GitHub URL")
    void parseCoordinates_standardUrl() {
        var coords = GitHubRepoFetcher.RepoCoordinates.parse(
                "https://github.com/spring-projects/spring-boot", "main");
        assertThat(coords.owner()).isEqualTo("spring-projects");
        assertThat(coords.repo()).isEqualTo("spring-boot");
    }

    @Test
    @DisplayName("RepoCoordinates parsed from URL with trailing slash")
    void parseCoordinates_trailingSlash() {
        var coords = GitHubRepoFetcher.RepoCoordinates.parse(
                "https://github.com/acme/my-service/", "develop");
        assertThat(coords.owner()).isEqualTo("acme");
        assertThat(coords.repo()).isEqualTo("my-service");
    }

    @Test
    @DisplayName("Malformed URL throws IllegalArgumentException")
    void parseCoordinates_malformedUrl_throws() {
        assertThatThrownBy(() ->
                GitHubRepoFetcher.RepoCoordinates.parse("https://github.com/only-owner", "main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid GitHub URL");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private MockResponse treeResponse(String json) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(json);
    }

    private MockResponse blobResponse(String content) {
        String encoded = Base64.getEncoder().encodeToString(content.getBytes());
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"content\":\"%s\"}".formatted(encoded));
    }
}
