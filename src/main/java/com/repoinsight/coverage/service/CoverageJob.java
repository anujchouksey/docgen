package com.repoinsight.coverage.service;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "coverage_jobs")
@Data
@NoArgsConstructor
public class CoverageJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false) private String devRepoUrl;
    @Column(nullable = false) private String devBranch;
    @Column(nullable = false) private String qaRepoUrl;
    @Column(nullable = false) private String qaBranch;

    private String layerFocus;
    private String reportFormat;
    private String analysisMode = "AI";  // AI | STATIC

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.QUEUED;

    @Column(columnDefinition = "TEXT")
    private String reportJson;     // full CoverageReport serialised as JSON

    private String errorMessage;
    private int devFilesAnalysed;
    private int qaFilesAnalysed;
    private long durationMs;

    private Instant createdAt = Instant.now();
    private Instant completedAt;

    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED }
}
