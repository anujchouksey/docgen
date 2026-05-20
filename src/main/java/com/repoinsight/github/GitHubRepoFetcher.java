package com.repoinsight.github;

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

@Component
@RequiredArgsConstructor
@Slf4j
public class GitHubRepoFetcher {

    private final WebClient githubWebClient;

    @Value("${github.max-file-size-bytes:102400}")
    private long maxFileSizeBytes;

    @Value("${github.max-files-per-repo:500}")
    private int maxFiles;

    @Value("${analysis.secret-scrub-patterns}")
    private List<String> secretPatterns;

    /**
     * Path segments to exclude — sourced from github.excluded-paths in application.yml.
     * GitHub API always returns paths with forward slashes, so these are OS-independent
     * substring checks against the repository-relative path (e.g. "src/test/java/...").
     */
    @Value("${github.excluded-paths}")
    private List<String> excludedPathSegments;

    public List<GitHubFile> fetchJavaFiles(String owner, String repo, String branch) {
        log.info("Fetching tree for {}/{} @ {}", owner, repo, branch);

        GitHubTreeResponse tree = githubWebClient.get()
                .uri("/repos/{owner}/{repo}/git/trees/{branch}?recursive=1", owner, repo, branch)
                .retrieve()
                .bodyToMono(GitHubTreeResponse.class)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2)))
                .block(Duration.ofSeconds(30));

        if (tree == null || tree.getTree() == null) {
            throw new IllegalStateException("Could not retrieve repo tree for %s/%s".formatted(owner, repo));
        }

        if (tree.isTruncated()) {
            log.warn("Repo tree is truncated for {}/{}; large repo — results may be partial", owner, repo);
        }

        List<GitHubTreeItem> javaBlobs = tree.getTree().stream()
                .filter(item -> "blob".equals(item.getType()))
                .filter(item -> item.getPath().endsWith(".java"))
                .filter(item -> !isExcluded(item.getPath()))
                .filter(item -> item.getSize() <= maxFileSizeBytes)
                .limit(maxFiles)
                .toList();

        log.info("Found {} Java files to fetch", javaBlobs.size());

        return Flux.fromIterable(javaBlobs)
                .flatMap(item -> fetchFileContent(owner, repo, item.getSha(), item.getPath()), 8)
                .collectList()
                .block(Duration.ofMinutes(3));
    }

    private Mono<GitHubFile> fetchFileContent(String owner, String repo, String sha, String path) {
        return githubWebClient.get()
                .uri("/repos/{owner}/{repo}/git/blobs/{sha}", owner, repo, sha)
                .retrieve()
                .bodyToMono(Map.class)
                .map(blob -> {
                    String encoded = (String) blob.get("content");
                    // Explicit UTF-8 — GitHub always stores source as UTF-8.
                    // Without this, new String(bytes) uses the JVM default charset
                    // (e.g. Windows-1252 on Windows), corrupting non-ASCII source.
                    String raw = new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8);
                    return GitHubFile.builder()
                            .path(path)
                            .sha(sha)
                            .sizeBytes(raw.length())
                            .content(scrubSecrets(raw))
                            .build();
                })
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)))
                .doOnError(e -> log.warn("Failed to fetch {}: {}", path, e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * GitHub paths are always forward-slash separated regardless of server OS.
     * We normalise each configured segment to use forward slashes before matching.
     */
    private boolean isExcluded(String path) {
        String normalised = path.replace('\\', '/');
        return excludedPathSegments.stream()
                .map(seg -> seg.replace('\\', '/').replace("*", ""))  // strip glob wildcards
                .anyMatch(normalised::contains);
    }

    private String scrubSecrets(String content) {
        String result = content;
        for (String pattern : secretPatterns) {
            result = result.replaceAll(pattern, "[REDACTED]");
        }
        return result;
    }

    public static record RepoCoordinates(String owner, String repo, String branch) {
        public static RepoCoordinates parse(String repoUrl, String branch) {
            // Always a URL: https://github.com/org/repo — forward slashes only
            String path = repoUrl.replace("https://github.com/", "").replaceAll("/$", "");
            String[] parts = path.split("/");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid GitHub URL: " + repoUrl);
            }
            return new RepoCoordinates(parts[0], parts[1], branch);
        }
    }
}
