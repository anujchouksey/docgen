package com.repoinsight.controller;

import com.repoinsight.service.AnalysisJob;
import com.repoinsight.service.RepoDocumentationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class RepoDocController {

    private final RepoDocumentationService service;

    @Value("${analysis.max-depth:5}")
    private int defaultMaxDepth;

    @PostMapping("/analyse")
    public ResponseEntity<Map<String, Object>> startAnalysis(@Valid @RequestBody AnalyseRequest request) {
        log.info("Analysis requested for {} @ {}", request.getRepoUrl(), request.getBranch());

        AnalysisJob job = service.createJob(request.getRepoUrl(), request.getBranch(),
                request.getOptions() != null && request.getOptions().getAnalysisMode() != null
                        ? request.getOptions().getAnalysisMode() : "AI");
        int depth = request.getOptions() != null && request.getOptions().getMaxDepth() > 0
                ? request.getOptions().getMaxDepth() : defaultMaxDepth;

        service.runJob(job.getId(), depth);

        URI pollUri = ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath("/api/v1/jobs/{id}")
                .buildAndExpand(job.getId())
                .toUri();

        return ResponseEntity.accepted().body(Map.of(
                "jobId", job.getId(),
                "status", job.getStatus(),
                "pollUrl", pollUri.toString()
        ));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<JobStatusResponse> getJob(@PathVariable String jobId) {
        AnalysisJob job = service.getJob(jobId);
        return ResponseEntity.ok(JobStatusResponse.from(job));
    }

    @GetMapping("/jobs/{jobId}/artifacts/business-logic")
    public ResponseEntity<String> getBusinessLogic(@PathVariable String jobId) {
        AnalysisJob job = requireCompleted(jobId);
        return ResponseEntity.ok()
                .header("Content-Type", "text/markdown")
                .body(job.getBusinessLogicDoc());
    }

    @GetMapping("/jobs/{jobId}/artifacts/method-hierarchy")
    public ResponseEntity<String> getMethodHierarchy(@PathVariable String jobId) {
        AnalysisJob job = requireCompleted(jobId);
        return ResponseEntity.ok()
                .header("Content-Type", "text/markdown")
                .body(job.getMethodHierarchyDoc());
    }

    @GetMapping("/jobs/{jobId}/artifacts/integrations")
    public ResponseEntity<String> getIntegrations(@PathVariable String jobId) {
        AnalysisJob job = requireCompleted(jobId);
        return ResponseEntity.ok()
                .header("Content-Type", "text/markdown")
                .body(job.getIntegrationsDoc());
    }

    @GetMapping("/jobs/{jobId}/artifacts/gherkin")
    public ResponseEntity<String> getGherkin(@PathVariable String jobId) {
        AnalysisJob job = requireCompleted(jobId);
        return ResponseEntity.ok()
                .header("Content-Type", "application/json")
                .body(job.getGherkinBundle());
    }

    private AnalysisJob requireCompleted(String jobId) {
        AnalysisJob job = service.getJob(jobId);
        if (job.getStatus() != AnalysisJob.JobStatus.COMPLETED) {
            throw new IllegalStateException("Job %s is not yet completed (status: %s)"
                    .formatted(jobId, job.getStatus()));
        }
        return job;
    }

    // ── Request / Response DTOs ────────────────────────────────────────────

    @Data
    public static class AnalyseRequest {
        @NotBlank
        @Pattern(regexp = "https://github\\.com/[\\w.-]+/[\\w.-]+",
                 message = "Must be a valid GitHub repository URL")
        private String repoUrl;

        @NotBlank
        private String branch = "main";

        private String githubToken;
        private Options options;

        @Data
        public static class Options {
            private boolean includeTests;
            private String outputFormat = "MARKDOWN";
            private int maxDepth = 5;
            private String analysisMode = "AI";  // AI | STATIC
        }
    }

    @Data
    public static class JobStatusResponse {
        private String jobId;
        private String status;
        private Integer filesAnalysed;
        private Long durationMs;
        private String error;
        private Artifacts artifacts;

        @Data
        public static class Artifacts {
            private String businessLogic;
            private String methodHierarchy;
            private String integrations;
            private String gherkin;
        }

        static JobStatusResponse from(AnalysisJob job) {
            JobStatusResponse r = new JobStatusResponse();
            r.setJobId(job.getId());
            r.setStatus(job.getStatus().name());
            r.setFilesAnalysed(job.getFilesAnalysed());
            r.setDurationMs(job.getDurationMs());
            r.setError(job.getErrorMessage());
            if (job.getStatus() == AnalysisJob.JobStatus.COMPLETED) {
                Artifacts a = new Artifacts();
                String base = "/api/v1/jobs/" + job.getId() + "/artifacts/";
                a.setBusinessLogic(base + "business-logic");
                a.setMethodHierarchy(base + "method-hierarchy");
                a.setIntegrations(base + "integrations");
                a.setGherkin(base + "gherkin");
                r.setArtifacts(a);
            }
            return r;
        }
    }
}
