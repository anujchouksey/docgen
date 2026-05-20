package com.repoinsight.coverage.service;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CoverageJobRepository extends JpaRepository<CoverageJob, String> {
}
