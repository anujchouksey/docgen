package com.repoinsight.static_analysis;

import com.repoinsight.analyzer.model.*;
import com.repoinsight.github.model.GitHubFile;
import com.repoinsight.static_analysis.model.ParsedClass;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Static-mode orchestrator. Equivalent to RepoAnalysisOrchestrator but uses
 * JavaParser AST analysis instead of an LLM — works offline, no API key needed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StaticAnalysisOrchestrator {

    private final JavaAstParser astParser;
    private final StaticBusinessLogicExtractor businessLogicExtractor;
    private final StaticIntegrationDetector integrationDetector;
    private final StaticMethodHierarchyBuilder hierarchyBuilder;
    private final TemplateGherkinGenerator gherkinGenerator;

    public AnalysisResult orchestrate(List<GitHubFile> files, String repoUrl,
                                      String branch, int maxDepth) {
        long start = System.currentTimeMillis();
        log.info("StaticOrchestrator: parsing {} files for {}", files.size(), repoUrl);

        // Step 1: parse all Java files into AST models
        List<ParsedClass> parsed = files.stream()
                .map(astParser::parse)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .toList();

        log.info("StaticOrchestrator: successfully parsed {}/{} files", parsed.size(), files.size());

        // Step 2: derive analysis artefacts (all in-process, fast)
        List<BusinessFlow> flows = businessLogicExtractor.extract(parsed);
        List<IntegrationPoint> integrations = integrationDetector.detect(parsed);
        List<MethodCallNode> trees = hierarchyBuilder.buildTrees(parsed, maxDepth);
        List<String> gherkin = gherkinGenerator.generate(flows, integrations);

        long durationMs = System.currentTimeMillis() - start;
        log.info("StaticOrchestrator: completed in {}ms — {} flows, {} integrations, {} trees",
                durationMs, flows.size(), integrations.size(), trees.size());

        return AnalysisResult.builder()
                .repoUrl(repoUrl)
                .branch(branch)
                .businessFlows(flows)
                .callHierarchies(trees)
                .integrationPoints(integrations)
                .gherkinFeatureFiles(gherkin)
                .stats(AnalysisResult.AnalysisStats.builder()
                        .filesAnalysed(files.size())
                        .durationMs(durationMs)
                        .build())
                .build();
    }
}
