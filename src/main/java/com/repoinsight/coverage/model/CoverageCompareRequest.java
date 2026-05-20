package com.repoinsight.coverage.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CoverageCompareRequest {

    @NotBlank
    @Pattern(regexp = "https://github\\.com/[\\w.\\-]+/[\\w.\\-]+|/.*|file://.*|[A-Za-z]:\\\\.*",
             message = "Must be a GitHub URL or an absolute local path")
    private String devRepoUrl;

    @NotBlank
    private String devBranch = "main";

    @NotBlank
    @Pattern(regexp = "https://github\\.com/[\\w.\\-]+/[\\w.\\-]+|/.*|file://.*|[A-Za-z]:\\\\.*",
             message = "Must be a GitHub URL or an absolute local path")
    private String qaRepoUrl;

    @NotBlank
    private String qaBranch = "main";

    private String githubToken;

    private String layerFocus = "ALL";    // ALL | SERVICE | CONTROLLER | REPOSITORY

    private String reportFormat = "FULL"; // FULL | GAPS_ONLY | EXECUTIVE

    private String analysisMode = "AI";   // AI | STATIC
}
