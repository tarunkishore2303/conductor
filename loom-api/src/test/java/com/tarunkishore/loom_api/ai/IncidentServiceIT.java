package com.tarunkishore.loom_api.ai;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.tarunkishore.loom_api.repository.IncidentVectorRepository;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "conductor.ai.incident-auto-index-enabled=true")
@AutoConfigureTestRestTemplate
@Testcontainers
@Import(IncidentServiceIT.Fakes.class)
class IncidentServiceIT {
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
        FakeIncidentClient client() {
            return new FakeIncidentClient();
        }
    }

    static class FakeIncidentClient implements IncidentAiClient {
        final AtomicInteger embeddings = new AtomicInteger(), syntheses = new AtomicInteger();
        volatile boolean unavailable, invalidCitation;

        public Embedding embed(String text) {
            embeddings.incrementAndGet();
            if (unavailable) throw new AiUnavailableException();
            var vector = new float[768];
            vector[text.contains("Invalid JSON") ? 1 : 0] = 1;
            return new Embedding(vector, "nomic-embed-text:v1.5");
        }

        public SynthesisResult synthesize(SynthesisRequest request) {
            syntheses.incrementAndGet();
            if (unavailable) throw new AiUnavailableException();
            UUID citation =
                    invalidCitation ? UUID.randomUUID() : request.matches().getFirst().incidentId();
            return new SynthesisResult(
                    new Synthesis(
                            "Retrieved incidents report the same dependency timeout.",
                            List.of(citation)),
                    "fake");
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TestRestTemplate http;
    @Autowired FakeIncidentClient client;
    @Autowired IncidentVectorRepository vectors;
    @Autowired ApplicationEventPublisher events;

    record Seed(UUID analysisId, UUID jobId, UUID taskId) {}

    @BeforeEach
    void reset() {
        jdbc.update("delete from ai_incident_embeddings");
        client.embeddings.set(0);
        client.syntheses.set(0);
        client.unavailable = false;
        client.invalidCitation = false;
    }

    Seed seed(String error) {
        var seed = new Seed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update(
                "insert into jobs(id,name,status,failure_policy,created_at,updated_at)"
                    + " values(?,?,'FAILED','FAIL_FAST',now(),now())",
                seed.jobId(),
                "incident-demo");
        jdbc.update(
                "insert into"
                    + " tasks(id,job_id,name,status,max_retries,retry_count,created_at,updated_at,dead_lettered_at)"
                    + " values(?,?,?,'DEAD_LETTERED',2,2,now(),now(),now())",
                seed.taskId(),
                seed.jobId(),
                "download");
        var timestamp = Instant.parse("2026-10-06T10:00:00Z");
        var facts =
                new FailureContext(
                        seed.jobId(),
                        "FAILED",
                        List.of(
                                new FailureContext.TaskEvidence(
                                        seed.taskId(),
                                        "download",
                                        "DEAD_LETTERED",
                                        2,
                                        List.of(
                                                new FailureContext.Attempt(
                                                        2,
                                                        "FAILED",
                                                        timestamp,
                                                        timestamp,
                                                        "DependencyError",
                                                        error,
                                                        null)),
                                        timestamp)),
                        List.of());
        var interpretation =
                new FailureAnalysisClient.Interpretation(
                        "Unverified recommendation",
                        "LOW",
                        "Not used in incident documents",
                        List.of("Inspect dependency"));
        jdbc.update(
                "insert into"
                    + " ai_failure_analyses(id,job_id,context_hash,facts_json,interpretation_json,model,generated_at,analysis_version)"
                    + " values(?,?,?,?,?,'fake',now(),1)",
                seed.analysisId(),
                seed.jobId(),
                UUID.randomUUID().toString(),
                mapper.writeValueAsString(facts),
                mapper.writeValueAsString(interpretation));
        return seed;
    }

    IncidentIndexService.IndexResult index(Seed seed) {
        var response =
                http.postForEntity(
                        "/api/v1/ai/failure-analyses/" + seed.analysisId() + "/index",
                        null,
                        IncidentIndexService.IndexResult.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    SimilarIncidentService.Retrieval retrieve(Seed seed) {
        var response =
                http.getForEntity(
                        "/api/v1/ai/runs/" + seed.jobId() + "/similar-incidents",
                        SimilarIncidentService.Retrieval.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    @Test
    void retrievalReferencesRealPriorRunExcludesCurrentAndUnrelatedAndSurvivesOutage() {
        var prior = seed("Dependency timeout");
        var current = seed("Dependency timeout");
        var unrelated = seed("Invalid JSON");
        index(prior);
        index(current);
        index(unrelated);
        client.unavailable = true;
        var found = retrieve(current);
        assertThat(found.matches()).hasSize(1);
        var match = found.matches().getFirst();
        assertThat(match.jobId()).isEqualTo(prior.jobId());
        assertThat(match.taskId()).isEqualTo(prior.taskId());
        assertThat(match.failureTimestamp()).isEqualTo(Instant.parse("2026-10-06T10:00:00Z"));
        assertThat(match.documentText())
                .contains("Dependency timeout")
                .doesNotContain("Unverified recommendation");
        var referenced =
                http.getForEntity(match.analysisUrl(), FailureAnalysisService.Analysis.class);
        assertThat(referenced.getBody().analysisId()).isEqualTo(prior.analysisId());
        assertThat(client.embeddings).hasValue(3);
        assertThat(client.syntheses).hasValue(0);
    }

    @Test
    void explicitIndexIdempotentAndUnindexedRunIs409() {
        var current = seed("Dependency timeout");
        assertThat(
                        http.getForEntity(
                                        "/api/v1/ai/runs/" + current.jobId() + "/similar-incidents",
                                        Map.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        assertThat(index(current).insertedCount()).isEqualTo(1);
        assertThat(index(current).insertedCount()).isZero();
        assertThat(client.embeddings).hasValue(1);
    }

    @Test
    void noMatchesUsesNoChatAndFabricatedCitationsRejected() {
        var current = seed("Dependency timeout");
        index(current);
        var none =
                http.postForEntity(
                        "/api/v1/ai/runs/" + current.jobId() + "/similar-incidents/synthesize",
                        null,
                        SimilarIncidentService.SynthesisResponse.class);
        assertThat(none.getStatusCode().value()).isEqualTo(200);
        assertThat(client.syntheses).hasValue(0);
        var prior = seed("Dependency timeout");
        index(prior);
        client.invalidCitation = true;
        assertThat(
                        http.postForEntity(
                                        "/api/v1/ai/runs/"
                                                + current.jobId()
                                                + "/similar-incidents/synthesize",
                                        null,
                                        Map.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(422);
        client.invalidCitation = false;
        var valid =
                http.postForEntity(
                        "/api/v1/ai/runs/" + current.jobId() + "/similar-incidents/synthesize",
                        null,
                        SimilarIncidentService.SynthesisResponse.class);
        assertThat(valid.getBody().synthesis().citedIncidentIds())
                .containsExactly(valid.getBody().retrieval().matches().getFirst().incidentId());
    }

    @Test
    void asynchronousEventIndexesWithoutChangingAnalysis() {
        var current = seed("Dependency timeout");
        events.publishEvent(new FailureAnalysisStored(current.analysisId()));
        await().atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                assertThat(
                                                vectors.findTaskEmbeddings(
                                                        current.analysisId(),
                                                        "nomic-embed-text:v1.5"))
                                        .hasSize(1));
        assertThat(
                        http.getForEntity(
                                        "/api/v1/ai/failure-analyses/" + current.analysisId(),
                                        FailureAnalysisService.Analysis.class)
                                .getBody()
                                .analysisId())
                .isEqualTo(current.analysisId());
    }
}
