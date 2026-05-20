package com.repoinsight.coverage.controller;

import com.repoinsight.coverage.model.CoverageCompareRequest;
import com.repoinsight.coverage.model.CoverageReport;
import com.repoinsight.coverage.service.CoverageComparisonService;
import com.repoinsight.coverage.service.CoverageJob;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/coverage")
@RequiredArgsConstructor
@Slf4j
public class CoverageController {

    private final CoverageComparisonService service;

    @PostMapping("/compare")
    public ResponseEntity<Map<String, Object>> startComparison(
            @Valid @RequestBody CoverageCompareRequest request) {

        log.info("Coverage comparison: {} vs {}", request.getDevRepoUrl(), request.getQaRepoUrl());

        CoverageJob job = service.createJob(request);
        service.runJob(job.getId());

        URI pollUri = ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath("/api/v1/coverage/jobs/{id}")
                .buildAndExpand(job.getId())
                .toUri();

        return ResponseEntity.accepted().body(Map.of(
                "jobId", job.getId(),
                "status", job.getStatus(),
                "pollUrl", pollUri.toString()
        ));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<JobStatusDto> getJobStatus(@PathVariable String jobId) {
        CoverageJob job = service.getJob(jobId);
        return ResponseEntity.ok(JobStatusDto.from(job));
    }

    @GetMapping("/jobs/{jobId}/report")
    public ResponseEntity<CoverageReport> getReport(@PathVariable String jobId) {
        return ResponseEntity.ok(service.getReport(jobId));
    }

    // ── DTO ───────────────────────────────────────────────────────────────

    record JobStatusDto(String jobId, String status, int devFilesAnalysed,
                        int qaFilesAnalysed, long durationMs, String error) {
        static JobStatusDto from(CoverageJob job) {
            return new JobStatusDto(
                    job.getId(), job.getStatus().name(),
                    job.getDevFilesAnalysed(), job.getQaFilesAnalysed(),
                    job.getDurationMs(), job.getErrorMessage());
        }
    }
}
