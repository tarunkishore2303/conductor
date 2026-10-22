package com.tarunkishore.loom_api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class TaskOutputMigrationIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg15").asCompatibleSubstituteFor("postgres"));

    @Test void upgradesV10HistoricalAttemptsAndEnforcesSuccessfulBoundedTypedOutput() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).target("10").load().migrate();
        var jdbc = new JdbcTemplate(source);
        UUID job = UUID.randomUUID(), task = UUID.randomUUID(), complete = UUID.randomUUID(), failed = UUID.randomUUID();
        jdbc.update("INSERT INTO jobs(id,name,status,failure_policy,created_at,updated_at) VALUES(?,'legacy','COMPLETE','FAIL_FAST',now(),now())", job);
        jdbc.update("INSERT INTO tasks(id,job_id,name,status,max_retries,retry_count,created_at,updated_at) VALUES(?,?,'legacy-task','COMPLETE',2,1,now(),now())", task, job);
        insertAttempt(jdbc, complete, task, "COMPLETE", 1);
        insertAttempt(jdbc, failed, task, "FAILED", 0);
        assertThat(Flyway.configure().dataSource(source).load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_executions WHERE output_text IS NULL AND output_content_type IS NULL", Integer.class)).isEqualTo(2);

        // Empty successful output is present output; null means no output capture.
        jdbc.update("UPDATE task_executions SET output_text='',output_content_type='application/json' WHERE id=?", complete);
        assertThat(jdbc.queryForObject("SELECT output_text FROM task_executions WHERE id=?", String.class, complete)).isEmpty();
        jdbc.update("UPDATE task_executions SET output_text=?,output_content_type='text/plain' WHERE id=?", "x".repeat(65536), complete);
        assertThat(jdbc.queryForObject("SELECT octet_length(output_text) FROM task_executions WHERE id=?", Integer.class, complete)).isEqualTo(65536);
        assertThatThrownBy(() -> jdbc.update("UPDATE task_executions SET output_text=? WHERE id=?", "x".repeat(65537), complete))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE task_executions SET output_text=? WHERE id=?", "界".repeat(21846), complete))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE task_executions SET output_content_type='text/html' WHERE id=?", complete))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE task_executions SET output_content_type=NULL WHERE id=?", complete))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE task_executions SET output_text='hidden',output_content_type='text/plain' WHERE id=?", failed))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE task_executions SET output_content_type='application/json' WHERE id=?", failed))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE task_executions SET status='FAILED' WHERE id=?", complete))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE task_executions SET output_text=NULL,output_content_type=NULL WHERE id=?", complete);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_executions WHERE output_text IS NULL", Integer.class)).isEqualTo(2);
    }

    private static void insertAttempt(JdbcTemplate jdbc, UUID id, UUID task, String status, int attempt) {
        jdbc.update("INSERT INTO task_executions(id,task_id,execution_id,status,started_at,completed_at,attempt_number) VALUES(?,?,?,?,now(),now(),?)",
                id, task, UUID.randomUUID(), status, attempt);
    }
}
