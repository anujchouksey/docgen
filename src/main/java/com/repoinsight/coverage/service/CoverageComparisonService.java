package com.repoinsight.coverage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoinsight.coverage.agent.CoverageComparisonAgent;
import com.repoinsight.coverage.model.CoverageCompareRequest;
import com.repoinsight.coverage.model.CoverageReport;
import com.repoinsight.github.GitHubRepoFetcher;
import com.repoinsight.github.model.GitHubFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CoverageComparisonService {

    private final GitHubRepoFetcher devFetcher;
    private final QARepoFetcher qaFetcher;
    private final CoverageComparisonAgent aiAgent;
    private final StaticCoverageAnalyser staticAnalyser;
    private final CoverageJobRepository jobRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public CoverageJob createJob(CoverageCompareRequest req) {
        CoverageJob job = new CoverageJob();
        job.setDevRepoUrl(req.getDevRepoUrl());
        job.setDevBranch(req.getDevBranch());
        job.setQaRepoUrl(req.getQaRepoUrl());
        job.setQaBranch(req.getQaBranch());
        job.setLayerFocus(req.getLayerFocus());
        job.setReportFormat(req.getReportFormat());
        job.setAnalysisMode(req.getAnalysisMode() != null ? req.getAnalysisMode() : "AI");
        return jobRepository.save(job);
    }

    @Async("agentExecutor")
    @Transactional
    public void runJob(String jobId) {
        CoverageJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Coverage job not found: " + jobId));

        job.setStatus(CoverageJob.Status.RUNNING);
        jobRepository.save(job);

        try {
            GitHubRepoFetcher.RepoCoordinates devCoords =
                    GitHubRepoFetcher.RepoCoordinates.parse(job.getDevRepoUrl(), job.getDevBranch());
            GitHubRepoFetcher.RepoCoordinates qaCoords =
                    GitHubRepoFetcher.RepoCoordinates.parse(job.getQaRepoUrl(), job.getQaBranch());

            log.info("Fetching dev files: {}/{}", devCoords.owner(), devCoords.repo());
            List<GitHubFile> devFiles = devFetcher.fetchJavaFiles(
                    devCoords.owner(), devCoords.repo(), devCoords.branch());

            log.info("Fetching QA files: {}/{}", qaCoords.owner(), qaCoords.repo());
            List<GitHubFile> qaFiles = qaFetcher.fetchTestFiles(
                    qaCoords.owner(), qaCoords.repo(), qaCoords.branch());

            boolean useAi = !"STATIC".equalsIgnoreCase(job.getAnalysisMode());
            log.info("Coverage job {}: running in {} mode", jobId, useAi ? "AI" : "STATIC");

            CoverageReport report = useAi
                    ? aiAgent.compare(devFiles, qaFiles,
                            job.getDevRepoUrl(), job.getQaRepoUrl(),
                            job.getDevBranch(), job.getQaBranch(),
                            jobId, job.getLayerFocus())
                    : staticAnalyser.analyse(devFiles, qaFiles,
                            job.getDevRepoUrl(), job.getQaRepoUrl(),
                            job.getDevBranch(), job.getQaBranch(),
                            jobId, job.getLayerFocus());

            job.setReportJson(objectMapper.writeValueAsString(report));
            job.setDevFilesAnalysed(report.getDevFilesAnalysed());
            job.setQaFilesAnalysed(report.getQaFilesAnalysed());
            job.setDurationMs(report.getDurationMs());
            job.setStatus(CoverageJob.Status.COMPLETED);
            job.setCompletedAt(Instant.now());

        } catch (Exception e) {
            log.error("Coverage job {} failed: {}", jobId, e.getMessage(), e);
            job.setStatus(CoverageJob.Status.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(Instant.now());
        }

        jobRepository.save(job);
    }

    @Transactional(readOnly = true)
    public CoverageJob getJob(String jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Coverage job not found: " + jobId));
    }

    @Transactional(readOnly = true)
    public CoverageReport getReport(String jobId) {
        CoverageJob job = getJob(jobId);
        if (job.getStatus() != CoverageJob.Status.COMPLETED) {
            throw new IllegalStateException("Job %s not completed (status: %s)".formatted(jobId, job.getStatus()));
        }
        try {
            return objectMapper.readValue(job.getReportJson(), CoverageReport.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not deserialise report for job " + jobId, e);
        }
    }
}
