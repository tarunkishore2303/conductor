package com.tarunkishore.loom_api.ai;

import static org.assertj.core.api.Assertions.*;

import com.tarunkishore.loom_api.repository.AiFailureAnalysisRepository;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@Import(FailureAnalysisIT.Fakes.class)
class FailureAnalysisIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg15").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeFailureClient client() {
            return new FakeFailureClient();
        }
    }

    static class FakeFailureClient implements FailureAnalysisClient {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean unavailable;
        volatile boolean malformed;

        public Result analyze(FailureContext facts) {
            calls.incrementAndGet();
            if (unavailable) throw new AiUnavailableException();
            if (malformed) return new Result(null, "fake");
            return new Result(
                    new Interpretation(
                            "Demo handler failure",
                            "HIGH",
                            "Persisted attempts report simulated errors",
                            List.of("Review the demo task configuration")),
                    "fake");
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired FakeFailureClient client;
    @Autowired AiFailureAnalysisRepository repository;

    @BeforeEach
    void reset() {
        client.unavailable = false;
        client.malformed = false;
        client.calls.set(0);
    }

    UUID seed(String jobStatus, boolean evidence) {
        UUID job = UUID.randomUUID(), task = UUID.randomUUID();
        jdbc.update(
                "insert into jobs(id,name,status,failure_policy,created_at,updated_at)"
                    + " values(?,?,?,'FAIL_FAST',now(),now())",
                job,
                "failed-demo",
                jobStatus);
        jdbc.update(
                "insert into"
                    + " tasks(id,job_id,name,status,max_retries,retry_count,created_at,updated_at,dead_lettered_at)"
                    + " values(?,?,?,'DEAD_LETTERED',2,2,now(),now(),now())",
                task,
                job,
                "[demo:fail] invoice");
        if (evidence)
            for (int n = 0; n < 3; n++)
                jdbc.update(
                        "insert into"
                            + " task_executions(id,task_id,execution_id,status,started_at,completed_at,attempt_number,error_type,error_message,retry_scheduled_at)"
                            + " values(?,?,?,'FAILED',now(),now(),?,?,?,?)",
                        UUID.randomUUID(),
                        task,
                        UUID.randomUUID(),
                        n,
                        "DemoFailure",
                        "password=hidden simulated failure",
                        n < 2 ? java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC) : null);
        return job;
    }

    FailureAnalysisService.Analysis analyze(UUID job) {
        var response =
                http.postForEntity(
                        "/api/v1/ai/runs/" + job + "/analyze-failure",
                        null,
                        FailureAnalysisService.Analysis.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    @Test
    void evidencePersistedAndCacheAvailableDuringModelOutage() {
        var job = seed("FAILED", true);
        var first = analyze(job);
        assertThat(first.facts().tasks().getFirst().attempts()).hasSize(3);
        assertThat(first.facts().tasks().getFirst().attempts().getFirst().errorMessage())
                .doesNotContain("hidden");
        client.unavailable = true;
        assertThat(analyze(job).analysisId()).isEqualTo(first.analysisId());
        var get =
                http.getForEntity(
                        "/api/v1/ai/runs/" + job + "/failure-analysis",
                        FailureAnalysisService.Analysis.class);
        assertThat(get.getBody().analysisId()).isEqualTo(first.analysisId());
        assertThat(client.calls).hasValue(1);
    }

    @Test
    void concurrentRequestsPersistOnlyOneAnalysis() throws Exception {
        var job = seed("FAILED", true);
        long before = repository.count();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<UUID> request =
                    () -> {
                        gate.await();
                        return analyze(job).analysisId();
                    };
            var a = executor.submit(request);
            var b = executor.submit(request);
            gate.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(b.get(10, TimeUnit.SECONDS));
        }
        assertThat(repository.count()).isEqualTo(before + 1);
    }

    @Test
    void invalidContextsAndProviderOutageDoNotPersist() {
        long before = repository.count();
        var successful = seed("COMPLETE", true);
        var legacy = seed("FAILED", false);
        var failed = seed("FAILED", true);
        assertThat(
                        http.postForEntity(
                                        "/api/v1/ai/runs/" + successful + "/analyze-failure",
                                        null,
                                        Map.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        assertThat(
                        http.postForEntity(
                                        "/api/v1/ai/runs/" + legacy + "/analyze-failure",
                                        null,
                                        Map.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(422);
        assertThat(
                        http.postForEntity(
                                        "/api/v1/ai/runs/" + UUID.randomUUID() + "/analyze-failure",
                                        null,
                                        Map.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
        client.unavailable = true;
        assertThat(
                        http.postForEntity(
                                        "/api/v1/ai/runs/" + failed + "/analyze-failure",
                                        null,
                                        Map.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(503);
        assertThat(repository.count()).isEqualTo(before);
    }

    @Test
    void malformedModelInterpretationIs422AndDoesNotPersist() {
        var job = seed("FAILED", true);
        long before = repository.count();
        client.malformed = true;
        assertThat(
                        http.postForEntity(
                                        "/api/v1/ai/runs/" + job + "/analyze-failure",
                                        null,
                                        Map.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(422);
        assertThat(repository.count()).isEqualTo(before);
    }
}
