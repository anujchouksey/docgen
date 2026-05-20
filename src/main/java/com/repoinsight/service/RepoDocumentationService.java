package com.repoinsight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoinsight.agent.RepoAnalysisOrchestrator;
import com.repoinsight.analyzer.model.AnalysisResult;
import com.repoinsight.generator.DocumentationGenerator;
import com.repoinsight.github.GitHubRepoFetcher;
import com.repoinsight.github.LocalRepoFetcher;
import com.repoinsight.github.model.GitHubFile;
import com.repoinsight.static_analysis.StaticAnalysisOrchestrator;
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
public class RepoDocumentationService {

    private final GitHubRepoFetcher repoFetcher;
    private final LocalRepoFetcher localFetcher;
    private final RepoAnalysisOrchestrator aiOrchestrator;
    private final StaticAnalysisOrchestrator staticOrchestrator;
    private final DocumentationGenerator docGenerator;
    private final AnalysisJobRepository jobRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public AnalysisJob createJob(String repoUrl, String branch, String mode) {
        AnalysisJob job = new AnalysisJob();
        job.setRepoUrl(repoUrl);
        job.setBranch(branch);
        job.setAnalysisMode(mode);
        job.setStatus(AnalysisJob.JobStatus.QUEUED);
        return jobRepository.save(job);
    }

    @Async("agentExecutor")
    @Transactional
    public void runJob(String jobId, int maxDepth) {
        AnalysisJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        job.setStatus(AnalysisJob.JobStatus.RUNNING);
        jobRepository.save(job);

        try {
            List<GitHubFile> files;
            if (LocalRepoFetcher.isLocalPath(job.getRepoUrl())) {
                files = localFetcher.fetchJavaFiles(job.getRepoUrl());
            } else {
                GitHubRepoFetcher.RepoCoordinates coords =
                        GitHubRepoFetcher.RepoCoordinates.parse(job.getRepoUrl(), job.getBranch());
                files = repoFetcher.fetchJavaFiles(coords.owner(), coords.repo(), coords.branch());
            }

            boolean useAi = !"STATIC".equalsIgnoreCase(job.getAnalysisMode());
            log.info("Job {}: running in {} mode", jobId, useAi ? "AI" : "STATIC");

            AnalysisResult result = useAi
                    ? aiOrchestrator.orchestrate(files, job.getRepoUrl(), job.getBranch(), maxDepth)
                    : staticOrchestrator.orchestrate(files, job.getRepoUrl(), job.getBranch(), maxDepth);

            job.setBusinessLogicDoc(
                    docGenerator.renderBusinessLogic(result.getBusinessFlows(), job.getRepoUrl()));
            job.setMethodHierarchyDoc(
                    docGenerator.renderMethodHierarchy(result.getCallHierarchies(), job.getRepoUrl()));
            job.setIntegrationsDoc(
                    docGenerator.renderIntegrations(result.getIntegrationPoints(), job.getRepoUrl()));
            job.setGherkinBundle(
                    objectMapper.writeValueAsString(result.getGherkinFeatureFiles()));

            job.setFilesAnalysed(result.getStats().getFilesAnalysed());
            job.setDurationMs(result.getStats().getDurationMs());
            job.setStatus(AnalysisJob.JobStatus.COMPLETED);
            job.setCompletedAt(Instant.now());

        } catch (Exception e) {
            log.error("Job {} failed: {}", jobId, e.getMessage(), e);
            job.setStatus(AnalysisJob.JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(Instant.now());
        }

        jobRepository.save(job);
    }

    @Transactional(readOnly = true)
    public AnalysisJob getJob(String jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
    }
}
