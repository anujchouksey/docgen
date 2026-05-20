package com.repoinsight.analyzer.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class AnalysisResult {
    String repoUrl;
    String branch;
    String commitSha;
    List<BusinessFlow> businessFlows;
    List<MethodCallNode> callHierarchies;     // one tree per entry point
    List<IntegrationPoint> integrationPoints;
    List<String> gherkinFeatureFiles;          // raw Gherkin text per feature
    AnalysisStats stats;

    @Value
    @Builder
    public static class AnalysisStats {
        int filesAnalysed;
        long tokensUsed;
        long durationMs;
    }
}
