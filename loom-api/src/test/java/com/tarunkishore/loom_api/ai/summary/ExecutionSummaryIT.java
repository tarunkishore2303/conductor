package com.tarunkishore.loom_api.ai.summary;

import static org.assertj.core.api.Assertions.*;

import com.tarunkishore.loom_api.repository.AiRunSummaryRepository;

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

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@Import(ExecutionSummaryIT.Fakes.class)
class ExecutionSummaryIT {
    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg15")
                            .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeSummaryClient client() {
            return new FakeSummaryClient();
        }
    }

    static class FakeSummaryClient implements ExecutionSummaryClient {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean unavailable, malformed;

        public Result summarize(ExecutionSummaryFacts facts) {
            calls.incrementAndGet();
            if (unavailable) throw new com.tarunkishore.loom_api.ai.AiUnavailableException();
            if (malformed) return new Result(null, "fake");
            return new Result(
                    new Interpretation(
                            "Summary of recorded " + facts.status() + " execution",
                            List.of(
                                    "Evidence contains "
                                            + facts.totalRecordedAttempts()
                                            + " persisted attempts"),
                            List.of(
                                    "Observed timestamps are not an exact workflow completion"
                                        + " timestamp")),
                    "fake");
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired FakeSummaryClient client;
    @Autowired AiRunSummaryRepository repository;

    record Seed(UUID jobId, UUID taskId, UUID lastAttemptId) {}

    @BeforeEach
    void reset() {
        client.calls.set(0);
        client.unavailable = false;
        client.malformed = false;
    }

    Seed seed(String jobStatus) {
        var seed = new Seed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String taskStatus = jobStatus.equals("FAILED") ? "DEAD_LETTERED" : "COMPLETE";
        jdbc.update(
                "insert into jobs(id,name,status,failure_policy,created_at,updated_at)"
                    + " values(?,?,?,'FAIL_FAST','2026-10-07T10:00:00Z','2026-10-07T10:00:05Z')",
                seed.jobId(),
                "summary-demo",
                jobStatus);
        jdbc.update(
                "insert into"
                    + " tasks(id,job_id,name,status,max_retries,retry_count,created_at,updated_at)"
                    + " values(?,?,?, ?,1,1,'2026-10-07T10:00:00Z','2026-10-07T10:00:05Z')",
                seed.taskId(),
                seed.jobId(),
                "demo",
                taskStatus);
        jdbc.update(
                "insert into"
                    + " task_executions(id,task_id,execution_id,status,started_at,completed_at,attempt_number,error_type,error_message,retry_scheduled_at)"
                    + " values(?,?,?,'FAILED','2026-10-07T10:00:00Z','2026-10-07T10:00:02Z',0,'Timeout','Dependency"
                    + " timeout','2026-10-07T10:00:03Z')",
                UUID.randomUUID(),
                seed.taskId(),
                UUID.randomUUID());
        jdbc.update(
                "insert into"
                    + " task_executions(id,task_id,execution_id,status,started_at,completed_at,attempt_number,error_type,error_message)"
                    + " values(?,?,?,?,'2026-10-07T10:00:03Z','2026-10-07T10:00:05Z',1,?,?)",
                seed.lastAttemptId(),
                seed.taskId(),
                UUID.randomUUID(),
                jobStatus.equals("FAILED") ? "FAILED" : "COMPLETE",
                jobStatus.equals("FAILED") ? "Timeout" : null,
                jobStatus.equals("FAILED") ? "Dependency timeout" : null);
        return seed;
    }

    ExecutionSummaryService.Summary generate(Seed seed) {
        var result =
                http.postForEntity(
                        "/api/v1/ai/runs/" + seed.jobId() + "/summary",
                        null,
                        ExecutionSummaryService.Summary.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        return result.getBody();
    }

    @Test
    void completeRunFactsPersistAndCacheSurvivesProviderOutage() {
        var seed = seed("COMPLETE");
        var summary = generate(seed);
        assertThat(summary.facts().completedTasks()).isEqualTo(1);
        assertThat(summary.facts().totalRecordedAttempts()).isEqualTo(2);
        assertThat(summary.facts().recordedRetries()).isEqualTo(1);
        assertThat(summary.facts().observedAttemptSpanMillis()).isEqualTo(5000);
        assertThat(summary.facts().aggregateRecordedTaskDurationMillis()).isEqualTo(4000);
        client.unavailable = true;
        jdbc.update(
                "update jobs set updated_at=updated_at+interval '10 seconds' where id=?",
                seed.jobId());
        assertThat(generate(seed)).isEqualTo(summary);
        var get =
                http.getForEntity(
                        "/api/v1/ai/runs/" + seed.jobId() + "/summary",
                        ExecutionSummaryService.Summary.class);
        assertThat(get.getBody()).isEqualTo(summary);
        assertThat(client.calls).hasValue(1);
    }

    @Test
    void changedEvidenceCreatesAnotherVersionAndConcurrentRequestsDeduplicate() throws Exception {
        var seed = seed("COMPLETE");
        var first = generate(seed);
        jdbc.update(
                "update task_executions set error_message='Different dependency failure' where"
                    + " task_id=? and attempt_number=0",
                seed.taskId());
        long before = repository.count();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<ExecutionSummaryService.Summary> request =
                    () -> {
                        gate.await();
                        return generate(seed);
                    };
            var a = executor.submit(request);
            var b = executor.submit(request);
            gate.countDown();
            var next = a.get(10, TimeUnit.SECONDS);
            var duplicate = b.get(10, TimeUnit.SECONDS);
            assertThat(next.summaryId()).isEqualTo(duplicate.summaryId());
            assertThat(next.contextHash()).isNotEqualTo(first.contextHash());
        }
        assertThat(repository.count()).isEqualTo(before + 1);
        assertThat(client.calls).hasValue(2);
    }

    @Test
    void failedSummaryCarriesExistingAnalysisReferenceWithoutReanalysis() {
        var seed = seed("FAILED");
        UUID analysisId = UUID.randomUUID();
        var facts =
                "{\"jobId\":\""
                        + seed.jobId()
                        + "\",\"jobStatus\":\"FAILED\",\"tasks\":[],\"unavailableEvidence\":[]}";
        var interpretation =
                "{\"likelyCause\":\"Demo"
                    + " timeout\",\"confidence\":\"HIGH\",\"explanation\":\"Recorded"
                    + " error\",\"recommendedActions\":[\"Inspect dependency\"]}";
        jdbc.update(
                "insert into"
                    + " ai_failure_analyses(id,job_id,context_hash,facts_json,interpretation_json,model,generated_at,analysis_version)"
                    + " values(?,?,?,?,?,'fake',now(),1)",
                analysisId,
                seed.jobId(),
                UUID.randomUUID().toString(),
                facts,
                interpretation);
        var summary = generate(seed);
        assertThat(summary.facts().deadLetteredTasks()).isEqualTo(1);
        assertThat(summary.facts().failureAnalysisId()).isEqualTo(analysisId);
    }

    @Test
    void providerErrorsAndUnsettledRunsNeverPersist() {
        var seed = seed("COMPLETE");
        long before = repository.count();
        client.unavailable = true;
        assertThat(
                        http.postForEntity(
                                        "/api/v1/ai/runs/" + seed.jobId() + "/summary",
                                        null,
                                        Map.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(503);
        client.unavailable = false;
        client.malformed = true;
        assertThat(
                        http.postForEntity(
                                        "/api/v1/ai/runs/" + seed.jobId() + "/summary",
                                        null,
                                        Map.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(422);
        var running = seed("RUNNING");
        assertThat(
                        http.postForEntity(
                                        "/api/v1/ai/runs/" + running.jobId() + "/summary",
                                        null,
                                        Map.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        assertThat(
                        http.postForEntity(
                                        "/api/v1/ai/runs/" + UUID.randomUUID() + "/summary",
                                        null,
                                        Map.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
        assertThat(repository.count()).isEqualTo(before);
    }
}
