package com.tarunkishore.loom_api.ai;

import com.tarunkishore.loom_api.dto.WorkflowTemplateRequest;
import com.tarunkishore.loom_api.dto.WorkflowTemplateResponse;
import com.tarunkishore.loom_api.repository.AiWorkflowProposalRepository;
import com.tarunkishore.loom_api.repository.WorkflowTemplateRepository;
import com.tarunkishore.loom_api.service.WorkflowService;
import com.loom.common.dto.TaskDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@Import(AiWorkflowProposalIT.FakeConfig.class)
class AiWorkflowProposalIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg15").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        AiWorkflowClient fakeClient() {
            return prompt -> {
                if (prompt.equals("unavailable")) throw new AiUnavailableException();
                var deps = prompt.equals("cycle") ? List.of("A")
                        : (prompt.equals("missing") ? List.of("missing") : List.<String>of());
                return new AiWorkflowClient.Generation(new GeneratedWorkflow("AI orders", "demo",
                        List.of(new GeneratedWorkflow.Task("A", "Download", "NOOP", deps, 2))), "fake");
            };
        }
    }
    @Autowired
    TestRestTemplate http;
    @Autowired
    WorkflowTemplateRepository templates;
    @Autowired
    AiWorkflowProposalRepository proposals;
    @MockitoSpyBean
    WorkflowService workflowService;

    @Test
    void approvalRollsBackTemplateAndApprovalMarkerOnFailure() {
        long before = templates.count();
        var p = generate("orders");
        doThrow(new IllegalStateException("forced response failure"))
                .when(workflowService).parseTasks(any());
        try {
            assertThat(http.postForEntity("/api/v1/ai/workflows/proposals/" + p.proposalId() + "/approve", null, Map.class)
                .getStatusCode().value()).isEqualTo(409);
            assertThat(templates.count()).isEqualTo(before);
            assertThat(proposals.findById(p.proposalId()).orElseThrow().getApprovedWorkflowId()).isNull();
        } finally {
            reset(workflowService);
        }
    }

    @Test
    void expiredProposalIs409() {
        var p = generate("orders");
        var entity = proposals.findById(p.proposalId()).orElseThrow();
        entity.setExpiresAt(Instant.now().minusSeconds(1));
        proposals.save(entity);
        assertThat(http.postForEntity("/api/v1/ai/workflows/proposals/" + p.proposalId() + "/approve", null, Map.class)
                .getStatusCode().value()).isEqualTo(409);
    }

    private AiWorkflowProposalService.Preview generate(String prompt) {
        var r = http.postForEntity("/api/v1/ai/workflows/generate", Map.of("prompt", prompt), AiWorkflowProposalService.Preview.class);
        assertThat(r
                .getStatusCode().value()).isEqualTo(200);
        return r.getBody();
    }

    @Test
    void previewThenApprovalUsesNormalPersistenceAndDuplicateIs409() {
        long before = templates.count();
        var p = generate("orders");
        assertThat(templates.count()).isEqualTo(before);
        var approved = http.postForEntity("/api/v1/ai/workflows/proposals/" + p.proposalId() + "/approve", null, WorkflowTemplateResponse.class);
        assertThat(approved
                .getStatusCode().value()).isEqualTo(201);
        assertThat(templates.count()).isEqualTo(before + 1);
        var fetched = http.getForEntity("/api/v1/workflows/" + approved.getBody().id(), WorkflowTemplateResponse.class);
        assertThat(fetched.getBody().tasks()).hasSize(1);
        assertThat(http.postForEntity("/api/v1/ai/workflows/proposals/" + p.proposalId() + "/approve", null, Map.class)
                .getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void invalidDagCannotBeApproved() {
        long before = templates.count();
        var p = generate("cycle");
        assertThat(p.validation().valid()).isFalse();
        assertThat(http.postForEntity("/api/v1/ai/workflows/proposals/" + p.proposalId() + "/approve", null, Map.class)
                .getStatusCode().value()).isEqualTo(422);
        assertThat(templates.count()).isEqualTo(before);
    }

    @Test
    void missingDependencyPreviewCannotBeApproved() {
        long before = templates.count();
        var p = generate("missing");
        assertThat(p.validation().valid()).isFalse();
        assertThat(p.validation().errors()).anyMatch(e -> e.contains("unknown"));
        assertThat(http.postForEntity("/api/v1/ai/workflows/proposals/" + p.proposalId() + "/approve", null, Map.class)
                .getStatusCode().value()).isEqualTo(422);
        assertThat(templates.count()).isEqualTo(before);
    }

    @Test
    void concurrentApprovalCreatesExactlyOneWorkflow() throws Exception {
        long before = templates.count();
        var p = generate("orders");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Integer> approval = () -> {
                gate.await();
                return http.postForEntity("/api/v1/ai/workflows/proposals/" + p.proposalId() + "/approve", null, Map.class).getStatusCode().value();
            };
            var a = pool.submit(approval);
            var b = pool.submit(approval);
            gate.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(templates.count()).isEqualTo(before + 1);
    }

    @Test
    void providerFailureDoesNotBreakManualCreation() {
        long before = proposals.count();
        assertThat(http.postForEntity("/api/v1/ai/workflows/generate", Map.of("prompt", "unavailable"), Map.class)
                .getStatusCode().value()).isEqualTo(503);
        assertThat(proposals.count()).isEqualTo(before);
        var manual = new WorkflowTemplateRequest("manual", null, List.of(new TaskDefinition("A", "A", List.of(), 0)), null);
        assertThat(http.postForEntity("/api/v1/workflows", manual, WorkflowTemplateResponse.class)
                .getStatusCode().value()).isEqualTo(201);
    }
}
