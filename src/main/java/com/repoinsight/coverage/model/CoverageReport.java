package com.repoinsight.coverage.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class CoverageReport {
    String jobId;
    String devRepoUrl;
    String qaRepoUrl;
    String devBranch;
    String qaBranch;
    Instant generatedAt;

    CoverageSummary summary;
    List<ClassCoverageReport> classes;
    String executiveSummary;      // AI-written 3–5 sentence summary for stakeholders

    // raw stats
    int devFilesAnalysed;
    int qaFilesAnalysed;
    long durationMs;
}
