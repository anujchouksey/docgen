package com.repoinsight.service;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, String> {
}
