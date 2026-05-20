package com.repoinsight.agent;

import com.repoinsight.analyzer.model.*;
import com.repoinsight.github.model.GitHubFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the four sub-agents in parallel where possible.
 *
 * Execution graph:
 *   files ──► BusinessLogicAgent  ─┐
 *   files ──► MethodHierarchyAgent─┤──► GherkinAgent ──► AnalysisResult
 *   files ──► IntegrationAgent    ─┘
 *
 * BusinessLogic + Integration run concurrently; both feed GherkinAgent.
 * MethodHierarchy runs concurrently with those two (independent).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RepoAnalysisOrchestrator {

    private final BusinessLogicAgent businessLogicAgent;
    private final MethodHierarchyAgent methodHierarchyAgent;
    private final IntegrationAgent integrationAgent;
    private final GherkinAgent gherkinAgent;

    public AnalysisResult orchestrate(List<GitHubFile> files, String repoUrl,
                                      String branch, int maxDepth) {
        long start = System.currentTimeMillis();
        log.info("Orchestrator: starting parallel agent execution for {}", repoUrl);

        // Tier 1: run three agents concurrently
        CompletableFuture<List<BusinessFlow>> bizFuture =
                CompletableFuture.supplyAsync(() -> businessLogicAgent.analyse(files));

        CompletableFuture<List<MethodCallNode>> hierFuture =
                CompletableFuture.supplyAsync(() -> methodHierarchyAgent.buildTrees(files, maxDepth));

        CompletableFuture<List<IntegrationPoint>> integFuture =
                CompletableFuture.supplyAsync(() -> integrationAgent.detect(files));

        // Wait for tier 1
        List<BusinessFlow> flows = bizFuture.join();
        List<MethodCallNode> trees = hierFuture.join();
        List<IntegrationPoint> integrations = integFuture.join();

        // Tier 2: Gherkin needs tier-1 outputs
        log.info("Orchestrator: tier-1 complete. Starting GherkinAgent.");
        List<String> features = gherkinAgent.generateFeatures(flows, integrations);

        long durationMs = System.currentTimeMillis() - start;
        log.info("Orchestrator: completed in {}ms", durationMs);

        return AnalysisResult.builder()
                .repoUrl(repoUrl)
                .branch(branch)
                .businessFlows(flows)
                .callHierarchies(trees)
                .integrationPoints(integrations)
                .gherkinFeatureFiles(features)
                .stats(AnalysisResult.AnalysisStats.builder()
                        .filesAnalysed(files.size())
                        .durationMs(durationMs)
                        .build())
                .build();
    }
}
