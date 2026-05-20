package com.repoinsight.coverage.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ClassCoverageReport {
    String devClass;               // simple class name
    String devFile;                // repo-relative path
    String layer;                  // SERVICE | CONTROLLER | REPOSITORY | COMPONENT | OTHER
    CoverageStatus status;

    List<String> coveredMethods;       // methods with QA coverage
    List<String> missedMethods;        // methods with no coverage
    List<String> missingScenarios;     // specific test cases that should exist but don't
    List<String> relevantQaFiles;      // QA files that (partially) cover this class

    String explanation;                // AI narrative: why this status was assigned
    String implementationNotes;        // dev class reference — what the code actually does
    String suggestedGherkin;           // ready-to-use Gherkin for the gaps
}
