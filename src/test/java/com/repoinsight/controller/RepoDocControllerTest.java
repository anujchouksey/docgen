package com.repoinsight.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoinsight.service.AnalysisJob;
import com.repoinsight.service.RepoDocumentationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RepoDocController.class)
@WithMockUser
class RepoDocControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RepoDocumentationService service;

    @Test
    @DisplayName("POST /api/v1/analyse returns 202 with jobId and pollUrl")
    void analyse_validRequest_returns202() throws Exception {
        AnalysisJob job = buildJob("job-001", AnalysisJob.JobStatus.QUEUED);
        when(service.createJob(anyString(), anyString())).thenReturn(job);

        mvc.perform(post("/api/v1/analyse")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repoUrl": "https://github.com/example/spring-orders",
                                  "branch": "main"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-001"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.pollUrl").exists());

        verify(service).runJob(eq("job-001"), anyInt());
    }

    @Test
    @DisplayName("POST /api/v1/analyse with blank repoUrl returns 400")
    void analyse_blankRepoUrl_returns400() throws Exception {
        mvc.perform(post("/api/v1/analyse")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "repoUrl": "", "branch": "main" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/analyse with GitLab URL returns 400")
    void analyse_nonGitHubUrl_returns400() throws Exception {
        mvc.perform(post("/api/v1/analyse")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "repoUrl": "https://gitlab.com/org/repo", "branch": "main" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/jobs/{id} returns job status")
    void getJob_existingJob_returnsStatus() throws Exception {
        AnalysisJob job = buildJob("job-001", AnalysisJob.JobStatus.RUNNING);
        when(service.getJob("job-001")).thenReturn(job);

        mvc.perform(get("/api/v1/jobs/job-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-001"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    @DisplayName("GET artifact for a running job returns 500 (job not completed)")
    void getArtifact_jobNotCompleted_throws() throws Exception {
        AnalysisJob job = buildJob("job-001", AnalysisJob.JobStatus.RUNNING);
        when(service.getJob("job-001")).thenReturn(job);

        mvc.perform(get("/api/v1/jobs/job-001/artifacts/business-logic"))
                .andExpect(status().is5xxServerError());
    }

    @Test
    @DisplayName("GET /api/v1/jobs/{id}/artifacts/business-logic returns Markdown for completed job")
    void getBusinessLogic_completedJob_returnsMarkdown() throws Exception {
        AnalysisJob job = buildJob("job-001", AnalysisJob.JobStatus.COMPLETED);
        job.setBusinessLogicDoc("# Business Logic\n\n## PlaceOrderFlow");
        when(service.getJob("job-001")).thenReturn(job);

        mvc.perform(get("/api/v1/jobs/job-001/artifacts/business-logic"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PlaceOrderFlow")));
    }

    @Test
    @DisplayName("Unauthenticated request is rejected with 401")
    @WithMockUser(roles = {})
    void analyse_unauthenticated_returns401() throws Exception {
        mvc.perform(post("/api/v1/analyse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is4xxClientError());
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private AnalysisJob buildJob(String id, AnalysisJob.JobStatus status) {
        AnalysisJob job = new AnalysisJob();
        try {
            var f = AnalysisJob.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(job, id);
        } catch (Exception ignored) {}
        job.setRepoUrl("https://github.com/example/spring-orders");
        job.setBranch("main");
        job.setStatus(status);
        return job;
    }
}
