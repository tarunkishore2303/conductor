package com.tarunkishore.loom_api.ai.copilot;

import com.tarunkishore.loom_api.ai.*;
import com.tarunkishore.loom_api.repository.IncidentVectorRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@Import(CopilotApiIT.Fakes.class)
class CopilotApiIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg15").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @TestConfiguration static class Fakes {
        @Bean @Primary FakeCopilotClient copilotClient() { return new FakeCopilotClient(); }
        @Bean @Primary FailureAnalysisClient failureClient() {
            return facts -> { throw new AssertionError("Read-only copilot must not generate failure analysis"); };
        }
    }
    static class FakeCopilotClient implements CopilotClient {
        final AtomicInteger calls = new AtomicInteger();
        public Result step(StepRequest request, Duration remaining) {
            calls.incrementAndGet();
            if (request.evidence().isEmpty()) {
                String tool = request.question().contains("before") ? "getSimilarIncidents"
                        : request.question().contains("fail?") ? "getFailureAnalysis" : "getRun";
                return new Result(new Step("TOOL", tool, new Arguments(null, null), null, List.of()), "fake");
            }
            return new Result(new Step("ANSWER", null, null, "The requested recorded evidence is attached.", List.of("E1")), "fake");
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TestRestTemplate http;
    @Autowired FakeCopilotClient client;
    @Autowired IncidentVectorRepository vectors;
    record Seed(UUID job, UUID task, UUID analysis) {}

    Seed seed() {
        var seed = new Seed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var start = Instant.parse("2026-10-07T10:00:00Z");
        jdbc.update("insert into jobs(id,name,status,failure_policy,created_at,updated_at) values(?,?,'FAILED','FAIL_FAST',?,?)",
                seed.job(), "copilot-test", java.sql.Timestamp.from(start), java.sql.Timestamp.from(start.plusSeconds(5)));
        jdbc.update("insert into tasks(id,job_id,name,status,max_retries,retry_count,created_at,updated_at,dead_lettered_at) values(?,?,?,'DEAD_LETTERED',2,2,?,?,?)",
                seed.task(), seed.job(), "timeout", java.sql.Timestamp.from(start), java.sql.Timestamp.from(start.plusSeconds(5)), java.sql.Timestamp.from(start.plusSeconds(5)));
        var attempts = new ArrayList<FailureContext.Attempt>();
        for (int number = 0; number < 3; number++) {
            var attemptStart = start.plusSeconds(number * 2L);
            jdbc.update("insert into task_executions(id,task_id,execution_id,status,started_at,completed_at,attempt_number,error_type,error_message) values(?,?,?,'FAILED',?,?,?,'Timeout','Connection timed out')",
                    UUID.randomUUID(), seed.task(), UUID.randomUUID(), java.sql.Timestamp.from(attemptStart), java.sql.Timestamp.from(attemptStart.plusSeconds(1)), number);
            attempts.add(new FailureContext.Attempt(number, "FAILED", attemptStart, attemptStart.plusSeconds(1), "Timeout", "Connection timed out", null));
        }
        var facts = new FailureContext(seed.job(), "FAILED", List.of(new FailureContext.TaskEvidence(seed.task(), "timeout", "DEAD_LETTERED", 2, attempts, start.plusSeconds(5))), List.of("External health unavailable"));
        var interpretation = new FailureAnalysisClient.Interpretation("Possible dependency timeout", "MEDIUM", "Observed connection timeouts", List.of("Inspect dependency health"));
        jdbc.update("insert into ai_failure_analyses(id,job_id,context_hash,facts_json,interpretation_json,model,generated_at,analysis_version) values(?,?,?,?,?,'fake',?,1)",
                seed.analysis(), seed.job(), "a".repeat(64), mapper.writeValueAsString(facts), mapper.writeValueAsString(interpretation), java.sql.Timestamp.from(start.plusSeconds(6)));
        float[] vector = new float[768]; vector[0] = 1;
        vectors.store(seed.analysis(), seed.job(), seed.task(), "nomic-embed-text:v1.5", "Connection timed out", vector);
        return seed;
    }

    CopilotService.Response query(UUID job, String question) {
        var response = http.postForEntity("/api/v1/ai/copilot/query", new CopilotService.Query(question, job, null, null), CopilotService.Response.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    @Test void retryAndStoredAnalysisQueriesUseRealReadDataWithoutWrites() {
        var seed = seed();
        int before = jdbc.queryForObject("select count(*) from ai_failure_analyses", Integer.class);
        var retries = query(seed.job(), "How many retries happened?");
        assertThat(mapper.readTree(retries.evidence().getFirst().factsJson()).get("recordedRetries").asInt()).isEqualTo(2);
        var analysis = query(seed.job(), "Why did this run fail?");
        assertThat(analysis.toolsUsed()).containsExactly("getFailureAnalysis");
        assertThat(analysis.evidence().getFirst().references()).anyMatch(r -> r.id().equals(seed.analysis()));
        assertThat(jdbc.queryForObject("select count(*) from ai_failure_analyses", Integer.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select retry_count from tasks where id=?", Integer.class, seed.task())).isEqualTo(2);
    }

    @Test void incidentQuestionReturnsActualHistoricalReferences() {
        var prior = seed(); var current = seed();
        var response = query(current.job(), "Have we seen this before?");
        assertThat(response.toolsUsed()).containsExactly("getSimilarIncidents");
        assertThat(response.evidence().getFirst().references()).anyMatch(r -> r.id().equals(prior.analysis()));
    }

    @Test void mutationQuestionAndInvalidUuidHaveNoToolSideEffects() {
        var seed = seed(); int before = client.calls.get();
        assertThat(query(seed.job(), "Retry this task.").toolsUsed()).isEmpty();
        assertThat(client.calls.get()).isEqualTo(before);
        var invalid = http.postForEntity("/api/v1/ai/copilot/query", Map.of("question", "state?", "jobId", "invalid"), String.class);
        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
    }

    @Test void missingStoredAnalysisProducesInspectableMissingEvidence() {
        var seed = seed();
        jdbc.update("delete from ai_incident_embeddings where analysis_id=?", seed.analysis());
        jdbc.update("delete from ai_failure_analyses where id=?", seed.analysis());
        var response = query(seed.job(), "Why did this run fail?");
        assertThat(response.evidence().getFirst().errorCode()).isEqualTo("MISSING_EVIDENCE");
    }
}
