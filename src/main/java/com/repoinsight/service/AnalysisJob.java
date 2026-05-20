package com.repoinsight.service;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "analysis_jobs")
@Data
@NoArgsConstructor
public class AnalysisJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String repoUrl;

    @Column(nullable = false)
    private String branch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status = JobStatus.QUEUED;

    @Column(columnDefinition = "TEXT")
    private String businessLogicDoc;

    @Column(columnDefinition = "TEXT")
    private String methodHierarchyDoc;

    @Column(columnDefinition = "TEXT")
    private String integrationsDoc;

    @Column(columnDefinition = "TEXT")
    private String gherkinBundle;    // JSON array of feature file strings

    private String analysisMode = "AI";  // AI | STATIC

    private String errorMessage;

    private int filesAnalysed;
    private long tokensUsed;
    private long durationMs;

    private Instant createdAt = Instant.now();
    private Instant completedAt;

    public enum JobStatus { QUEUED, RUNNING, COMPLETED, FAILED }
}
