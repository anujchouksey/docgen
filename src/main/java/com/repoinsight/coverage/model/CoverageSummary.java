package com.repoinsight.coverage.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CoverageSummary {
    int totalDevClasses;
    int covered;
    int partial;
    int notCovered;
    int notNeeded;
    double coverageScore;   // (covered + partial*0.5) / (total - notNeeded) * 100
}
