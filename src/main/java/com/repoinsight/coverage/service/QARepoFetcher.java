package com.repoinsight.coverage.service;

import com.repoinsight.github.GitHubRepoFetcher;
import com.repoinsight.github.model.GitHubFile;
import com.repoinsight.github.model.GitHubTreeItem;
import com.repoinsight.github.model.GitHubTreeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Fetches QA test files from a repository.
 * Accepts: *Test.java, *Spec.java, *IT.java, *.feature, *Steps.java,
 *          *StepDefs.java, *StepDefinitions.java, *TestCase.java
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QARepoFetcher {

    private final WebClient githubWebClient;

    @Value("${github.max-file-size-bytes:102400}")
    private long maxFileSizeBytes;

    @Value("${github.max-files-per-repo:500}")
    private int maxFiles;

    /** Name suffixes that identify BDD / test files to fetch from the QA repo. */
    private static final List<String> QA_SUFFIXES = List.of(
            // Feature files (Gherkin)
            ".feature",
            // Step definitions — explicit suffixes
            "Steps.java", "StepDefs.java", "StepDefinitions.java",
            "StepDef.java", "Step.java",
            // Hooks
            "Hooks.java", "Hook.java",
            // Cucumber runners / configuration
            "Runner.java", "RunTest.java", "RunTests.java",
            "RunCucumber.java", "CucumberTest.java",
            "CucumberContextConfiguration.java",
            // Classic JUnit/TestNG tests that may have step-def style assertions
            "Test.java", "Tests.java", "Spec.java", "IT.java",
            "TestCase.java", "E2ETest.java", "IntegrationTest.java",
            // Cucumber config files
            "cucumber.properties", "junit-platform.properties"
    );

    /** Extra name fragments that identify BDD glue files regardless of suffix. */
    private static final List<String> BDD_NAME_HINTS = List.of(
            "Steps", "StepDef", "Hooks", "Hook", "Glue", "Runner", "CucumberContext"
    );

    public List<GitHubFile> fetchTestFiles(String owner, String repo, String branch) {
        log.info("QARepoFetcher: fetching test files from {}/{} @ {}", owner, repo, branch);

        GitHubTreeResponse tree = githubWebClient.get()
                .uri("/repos/{owner}/{repo}/git/trees/{branch}?recursive=1", owner, repo, branch)
                .retrieve()
                .bodyToMono(GitHubTreeResponse.class)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2)))
                .block(Duration.ofSeconds(30));

        if (tree == null || tree.getTree() == null) {
            throw new IllegalStateException("Could not retrieve QA repo tree for %s/%s".formatted(owner, repo));
        }

        List<GitHubTreeItem> testBlobs = tree.getTree().stream()
                .filter(item -> "blob".equals(item.getType()))
                .filter(item -> isTestFile(item.getPath()))
                .filter(item -> item.getSize() <= maxFileSizeBytes)
                .limit(maxFiles)
                .toList();

        log.info("QARepoFetcher: found {} test files to fetch", testBlobs.size());

        return Flux.fromIterable(testBlobs)
                .flatMap(item -> fetchContent(owner, repo, item.getSha(), item.getPath()), 8)
                .collectList()
                .block(Duration.ofMinutes(3));
    }

    private boolean isTestFile(String path) {
        if (QA_SUFFIXES.stream().anyMatch(path::endsWith)) return true;
        // Also pick up any .java file whose name contains a BDD hint word
        String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        return fileName.endsWith(".java")
                && BDD_NAME_HINTS.stream().anyMatch(hint -> fileName.contains(hint));
    }

    private Mono<GitHubFile> fetchContent(String owner, String repo, String sha, String path) {
        return githubWebClient.get()
                .uri("/repos/{owner}/{repo}/git/blobs/{sha}", owner, repo, sha)
                .retrieve()
                .bodyToMono(Map.class)
                .map(blob -> {
                    String encoded = (String) blob.get("content");
                    // Explicit UTF-8 — prevents garbled output on Windows where the
                    // JVM default charset is typically Windows-1252.
                    String raw = new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8);
                    return GitHubFile.builder()
                            .path(path).sha(sha).sizeBytes(raw.length()).content(raw).build();
                })
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)))
                .doOnError(e -> log.warn("Failed to fetch QA file {}: {}", path, e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }
}
