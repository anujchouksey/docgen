package com.repoinsight.coverage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoinsight.coverage.controller.CoverageController;
import com.repoinsight.coverage.model.*;
import com.repoinsight.coverage.service.CoverageComparisonService;
import com.repoinsight.coverage.service.CoverageJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CoverageController.class)
@WithMockUser
class CoverageControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean  private CoverageComparisonService service;

    @Test
    @DisplayName("POST /compare returns 202 with jobId")
    void compare_validRequest_returns202() throws Exception {
        CoverageJob job = buildJob("cov-001", CoverageJob.Status.QUEUED);
        when(service.createJob(any())).thenReturn(job);

        mvc.perform(post("/api/v1/coverage/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "devRepoUrl": "https://github.com/org/dev-service",
                                  "devBranch": "main",
                                  "qaRepoUrl":  "https://github.com/org/qa-tests",
                                  "qaBranch":   "main"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("cov-001"))
                .andExpect(jsonPath("$.pollUrl").exists());

        verify(service).runJob("cov-001");
    }

    @Test
    @DisplayName("POST /compare with invalid dev URL returns 400")
    void compare_invalidDevUrl_returns400() throws Exception {
        mvc.perform(post("/api/v1/coverage/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "devRepoUrl": "https://gitlab.com/org/repo",
                                  "devBranch":  "main",
                                  "qaRepoUrl":  "https://github.com/org/qa",
                                  "qaBranch":   "main"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /jobs/{id} returns status and stats")
    void getJobStatus_existingJob_returnsStatus() throws Exception {
        CoverageJob job = buildJob("cov-001", CoverageJob.Status.RUNNING);
        when(service.getJob("cov-001")).thenReturn(job);

        mvc.perform(get("/api/v1/coverage/jobs/cov-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("cov-001"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    @DisplayName("GET /jobs/{id}/report returns coverage report for completed job")
    void getReport_completedJob_returnsReport() throws Exception {
        CoverageReport report = buildReport();
        when(service.getReport("cov-001")).thenReturn(report);

        mvc.perform(get("/api/v1/coverage/jobs/cov-001/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.coverageScore").value(75.0))
                .andExpect(jsonPath("$.classes[0].devClass").value("OrderService"))
                .andExpect(jsonPath("$.executiveSummary").exists());
    }

    @Test
    @DisplayName("GET /jobs/{id}/report for running job returns 500")
    void getReport_runningJob_returns500() throws Exception {
        when(service.getReport("cov-001"))
                .thenThrow(new IllegalStateException("not completed"));

        mvc.perform(get("/api/v1/coverage/jobs/cov-001/report"))
                .andExpect(status().isInternalServerError());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private CoverageJob buildJob(String id, CoverageJob.Status status) {
        CoverageJob job = new CoverageJob();
        try {
            var f = CoverageJob.class.getDeclaredField("id");
            f.setAccessible(true); f.set(job, id);
        } catch (Exception ignored) {}
        job.setDevRepoUrl("https://github.com/org/dev");
        job.setDevBranch("main");
        job.setQaRepoUrl("https://github.com/org/qa");
        job.setQaBranch("main");
        job.setStatus(status);
        return job;
    }

    private CoverageReport buildReport() {
        ClassCoverageReport cls = ClassCoverageReport.builder()
                .devClass("OrderService").devFile("OrderService.java").layer("SERVICE")
                .status(CoverageStatus.PARTIAL)
                .coveredMethods(List.of("createOrder")).missedMethods(List.of("cancelOrder"))
                .missingScenarios(List.of("cancel after shipment")).relevantQaFiles(List.of("OrderTest.java"))
                .explanation("Partial — only happy path covered.").implementationNotes("Creates orders via JPA.")
                .suggestedGherkin("Scenario: cancel shipped order\n  Given ...\n  When ...\n  Then ...").build();

        return CoverageReport.builder()
                .jobId("cov-001").devRepoUrl("https://github.com/org/dev").qaRepoUrl("https://github.com/org/qa")
                .devBranch("main").qaBranch("main").generatedAt(Instant.now())
                .summary(CoverageSummary.builder().totalDevClasses(4).covered(2).partial(1)
                        .notCovered(1).notNeeded(0).coverageScore(75.0).build())
                .classes(List.of(cls)).executiveSummary("Coverage is 75%. Service layer has gaps.")
                .devFilesAnalysed(10).qaFilesAnalysed(5).durationMs(12000).build();
    }
}
