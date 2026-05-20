package com.repoinsight.analyzer.model;

import com.repoinsight.github.model.GitHubFile;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class AnalysisContext {
    String repoUrl;
    String branch;
    List<GitHubFile> sourceFiles;
    int maxDepth;
}
