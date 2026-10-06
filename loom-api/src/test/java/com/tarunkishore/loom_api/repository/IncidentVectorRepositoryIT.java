package com.tarunkishore.loom_api.repository;

import org.junit.jupiter.api.Test;
import com.tarunkishore.loom_api.ai.AiOutputException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class IncidentVectorRepositoryIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg15").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired IncidentVectorRepository repository;
    @Autowired JdbcTemplate jdbc;

    private Fixture fixture() {
        var fixture = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("""
                INSERT INTO jobs(id,name,status,failure_policy,created_at,updated_at,version)
                VALUES(?,'incident','FAILED','FAIL_FAST',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                """, fixture.jobId());
        jdbc.update("""
                INSERT INTO tasks(id,job_id,name,status,max_retries,retry_count,created_at,updated_at,version)
                VALUES(?,?,'failed-step','DEAD_LETTERED',2,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                """, fixture.taskId(), fixture.jobId());
        jdbc.update("""
                INSERT INTO ai_failure_analyses
                    (id,job_id,context_hash,facts_json,interpretation_json,model,generated_at,analysis_version)
                VALUES(?,?,?,'{}','{}','fake',CURRENT_TIMESTAMP,1)
                """, fixture.analysisId(), fixture.jobId(), UUID.randomUUID().toString());
        return fixture;
    }

    private float[] axis(int coordinate) {
        float[] vector = new float[768];
        vector[coordinate] = 1;
        return vector;
    }

    @Test
    void cosineRetrievalReturnsRealMetadataExcludesCurrentRunAndOtherModelsAndRespectsBounds() {
        String model = UUID.randomUUID().toString();
        var current = fixture();
        var prior = fixture();
        var unrelated = fixture();
        repository.store(current.analysisId(), current.jobId(), current.taskId(), model, "current timeout", axis(0));
        repository.store(prior.analysisId(), prior.jobId(), prior.taskId(), model, "prior timeout", axis(0));
        repository.store(unrelated.analysisId(), unrelated.jobId(), unrelated.taskId(), model, "JSON parsing", axis(1));
        repository.store(unrelated.analysisId(), unrelated.jobId(), unrelated.taskId(), model + "-other", "different model", axis(0));

        var matches = repository.search(current.jobId(), model, axis(0), 3, 0.8);
        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().analysisId()).isEqualTo(prior.analysisId());
        assertThat(matches.getFirst().jobId()).isEqualTo(prior.jobId());
        assertThat(matches.getFirst().taskId()).isEqualTo(prior.taskId());
        assertThat(matches.getFirst().document()).isEqualTo("prior timeout");
        assertThat(matches.getFirst().similarity()).isEqualTo(1);
        assertThat(repository.search(current.jobId(), model, axis(2), 3, 0.8)).isEmpty();
        assertThat(repository.search(current.jobId(), model, axis(0), 1, 0)).hasSize(1);
    }

    @Test
    void insertIsIdempotentAndCurrentQueryVectorIsReusableWithoutNewEmbedding() {
        var fixture = fixture();
        assertThat(repository.store(fixture.analysisId(), fixture.jobId(), fixture.taskId(), "test", "timeout", axis(0)))
                .isTrue();
        assertThat(repository.store(fixture.analysisId(), fixture.jobId(), fixture.taskId(), "test", "replacement", axis(1)))
                .isFalse();
        var stored = repository.findTaskEmbeddings(fixture.analysisId(), "test");
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().document()).isEqualTo("timeout");
        assertThat(stored.getFirst().embedding()).containsExactly(axis(0));
        assertThat(repository.findTaskEmbeddings(fixture.analysisId(), "other")).isEmpty();
    }

    @Test
    void mismatchedTaskOwnershipAndInvalidVectorsCannotBeInserted() {
        var one = fixture();
        var two = fixture();
        assertThatThrownBy(() -> repository.store(one.analysisId(), one.jobId(), two.taskId(), "test", "bad", axis(0)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThatThrownBy(() -> repository.store(one.analysisId(), one.jobId(), one.taskId(), "test", "bad", new float[768]))
                .isInstanceOf(AiOutputException.class);
        assertThat(repository.findTaskEmbeddings(one.analysisId(), "test")).isEmpty();
    }

    @Test
    void malformedStoredZeroEmbeddingIsExcludedFromSimilarityResults() {
        var fixture = fixture();
        String model = UUID.randomUUID().toString();
        String zero = "[" + "0,".repeat(767) + "0]";
        jdbc.update("""
                INSERT INTO ai_incident_embeddings
                    (id,analysis_id,job_id,task_id,embedding_model,embedding,document_text)
                VALUES(?,?,?,?,?,CAST(? AS vector),'malformed zero')
                """, UUID.randomUUID(), fixture.analysisId(), fixture.jobId(), fixture.taskId(), model, zero);
        assertThat(repository.search(UUID.randomUUID(), model, axis(0), 3, 0)).isEmpty();
        assertThatThrownBy(() -> repository.findTaskEmbeddings(fixture.analysisId(), model))
                .isInstanceOf(AiOutputException.class);
    }

    private record Fixture(UUID analysisId, UUID jobId, UUID taskId) {}
}
