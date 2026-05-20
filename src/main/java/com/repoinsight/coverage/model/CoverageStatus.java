package com.repoinsight.coverage.model;

public enum CoverageStatus {
    COVERED,      // all public methods have corresponding test coverage
    PARTIAL,      // some methods covered, others missing; or coverage lacks edge cases
    MISSED,       // no QA test maps to this class at all
    NOT_NEEDED    // boilerplate: DTO, config, entity getter/setter, generated code
}
