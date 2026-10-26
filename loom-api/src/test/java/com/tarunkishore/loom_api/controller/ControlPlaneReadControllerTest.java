package com.tarunkishore.loom_api.controller;

import com.loom.common.model.FailurePolicy;
import com.loom.common.model.JobStatus;
import com.tarunkishore.loom_api.dto.JobSummaryResponse;
import com.tarunkishore.loom_api.dto.WorkflowTemplateResponse;
import com.tarunkishore.loom_api.exception.GlobalExceptionHandler;
import com.tarunkishore.loom_api.service.JobService;
import com.tarunkishore.loom_api.service.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ControlPlaneReadControllerTest {
    private JobService jobs;
    private WorkflowService workflows;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        jobs = mock(JobService.class);
        workflows = mock(WorkflowService.class);
        mvc = MockMvcBuilders.standaloneSetup(new JobController(jobs), new WorkflowController(workflows))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void runListDefaultsToFiftyAndDoesNotExposeTasksOrOutputs() throws Exception {
        var id = UUID.randomUUID();
        when(jobs.listJobs(50)).thenReturn(List.of(new JobSummaryResponse(id, "orders", JobStatus.COMPLETE,
                FailurePolicy.FAIL_FAST, Instant.EPOCH, Instant.EPOCH)));
        mvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].status").value("COMPLETE"))
                .andExpect(jsonPath("$[0].tasks").doesNotExist())
                .andExpect(jsonPath("$[0].outputText").doesNotExist());
        verify(jobs).listJobs(50);
    }

    @Test void workflowListReturnsExistingResponseShapeAndRequestedLimit() throws Exception {
        var id = UUID.randomUUID();
        when(workflows.listTemplates(1)).thenReturn(List.of(new WorkflowTemplateResponse(id, "orders", "Process orders",
                List.of(), FailurePolicy.FAIL_FAST, Instant.EPOCH)));
        mvc.perform(get("/api/v1/workflows?limit=1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].tasks").isArray())
                .andExpect(jsonPath("$[0].defaultFailurePolicy").value("FAIL_FAST"));
        verify(workflows).listTemplates(1);
    }

    @Test void invalidRunListLimitsAreRejectedBeforeQuerying() throws Exception {
        mvc.perform(get("/api/v1/jobs?limit=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/jobs?limit=101")).andExpect(status().isBadRequest());
        verifyNoInteractions(jobs);
    }

    @Test void invalidWorkflowListLimitsAreRejectedBeforeQuerying() throws Exception {
        mvc.perform(get("/api/v1/workflows?limit=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/workflows?limit=101")).andExpect(status().isBadRequest());
        verifyNoInteractions(workflows);
    }

    @Test void malformedLimitReturnsSafeBadRequest() throws Exception {
        mvc.perform(get("/api/v1/jobs?limit=invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request body or parameter format is invalid."));
        verifyNoInteractions(jobs);
    }

    @Test void emptyListsRemainSuccessfulResponses() throws Exception {
        when(jobs.listJobs(50)).thenReturn(List.of());
        when(workflows.listTemplates(50)).thenReturn(List.of());
        mvc.perform(get("/api/v1/jobs")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/v1/workflows")).andExpect(status().isOk()).andExpect(content().json("[]"));
    }
}
