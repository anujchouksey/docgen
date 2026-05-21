package com.repoinsight.github;

import com.repoinsight.github.model.GitHubFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Component
@Slf4j
public class LocalRepoFetcher {

    private static final List<String> QA_SUFFIXES = List.of(
            ".feature",
            "Steps.java", "StepDefs.java", "StepDefinitions.java", "StepDef.java", "Step.java",
            "Hooks.java", "Hook.java",
            "Runner.java", "RunTest.java", "RunTests.java",
            "RunCucumber.java", "CucumberTest.java", "CucumberContextConfiguration.java",
            "Test.java", "Tests.java", "Spec.java", "IT.java",
            "TestCase.java", "E2ETest.java", "IntegrationTest.java",
            "cucumber.properties", "junit-platform.properties"
    );

    private static final List<String> BDD_NAME_HINTS = List.of(
            "Steps", "StepDef", "Hooks", "Hook", "Glue", "Runner", "CucumberContext"
    );

    @Value("${github.max-file-size-bytes:102400}")
    private long maxFileSizeBytes;

    @Value("${github.max-files-per-repo:500}")
    private int maxFiles;

    @Value("${analysis.secret-scrub-patterns}")
    private List<String> secretPatterns;

    @Value("${github.excluded-paths}")
    private List<String> excludedPathSegments;

    public List<GitHubFile> fetchJavaFiles(String localPath) {
        Path root = toPath(localPath);
        log.info("Reading Java source files from local path: {}", root);
        return walk(root, p -> p.toString().endsWith(".java") && !isTestFile(p));
    }

    public List<GitHubFile> fetchTestFiles(String localPath) {
        Path root = toPath(localPath);
        log.info("Reading test files from local path: {}", root);
        return walk(root, p -> isTestFile(p) || p.toString().endsWith(".feature"));
    }

    private List<GitHubFile> walk(Path root, Predicate<Path> fileFilter) {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Local path is not a directory: " + root);
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(fileFilter)
                    .filter(p -> !isExcluded(root.relativize(p).toString()))
                    .filter(p -> sizeOk(p))
                    .limit(maxFiles)
                    .map(p -> toGitHubFile(root, p))
                    .filter(f -> f != null)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to walk local path: " + root, e);
        }
    }

    private GitHubFile toGitHubFile(Path root, Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String relativePath = root.relativize(file).toString().replace('\\', '/');
            return GitHubFile.builder()
                    .path(relativePath)
                    .sha("")
                    .sizeBytes(content.length())
                    .content(scrubSecrets(content))
                    .build();
        } catch (IOException e) {
            log.warn("Could not read local file {}: {}", file, e.getMessage());
            return null;
        }
    }

    private boolean isTestFile(Path path) {
        String name = path.getFileName().toString();
        if (QA_SUFFIXES.stream().anyMatch(name::endsWith)) return true;
        // BDD name hints in file name
        if (name.endsWith(".java") && BDD_NAME_HINTS.stream().anyMatch(name::contains)) return true;
        // Content-based detection for local files: any .java with step annotations
        if (name.endsWith(".java")) {
            try {
                String preview = Files.readString(path, StandardCharsets.UTF_8);
                // Read only first 4KB to keep it fast
                if (preview.length() > 4096) preview = preview.substring(0, 4096);
                return preview.contains("@Given") || preview.contains("@When") || preview.contains("@Then");
            } catch (IOException ignored) {}
        }
        return false;
    }

    private boolean isExcluded(String relativePath) {
        String normalised = relativePath.replace('\\', '/');
        return excludedPathSegments.stream()
                .map(seg -> seg.replace('\\', '/').replace("*", ""))
                .anyMatch(normalised::contains);
    }

    private boolean sizeOk(Path p) {
        try { return Files.size(p) <= maxFileSizeBytes; }
        catch (IOException e) { return false; }
    }

    private String scrubSecrets(String content) {
        String result = content;
        for (String pattern : secretPatterns) {
            result = result.replaceAll(pattern, "[REDACTED]");
        }
        return result;
    }

    public static Path toPath(String url) {
        return url.startsWith("file://") ? Path.of(url.substring(7)) : Path.of(url);
    }

    /** True when the value is an absolute local filesystem path, not a GitHub URL. */
    public static boolean isLocalPath(String url) {
        if (url == null) return false;
        return url.startsWith("/")          // Unix/macOS absolute
                || url.startsWith("file://") // file URI
                || (url.length() > 2 && url.charAt(1) == ':'); // Windows C:\...
    }
}
