package com.tarunkishore.loom_api.outbox;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class OutboxMigrationIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg15").asCompatibleSubstituteFor("postgres"));

    @Test void v11UpgradePreservesHistoryAndEnforcesOutboxBounds() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).target("11").load().migrate();
        var jdbc = new JdbcTemplate(source);
        UUID job = UUID.randomUUID(), task = UUID.randomUUID(), execution = UUID.randomUUID();
        jdbc.update("INSERT INTO jobs(id,name,status,failure_policy,created_at,updated_at) VALUES(?,'legacy','COMPLETE','FAIL_FAST',now(),now())", job);
        jdbc.update("INSERT INTO tasks(id,job_id,name,status,max_retries,retry_count,created_at,updated_at) VALUES(?,?,'legacy','COMPLETE',2,0,now(),now())", task, job);
        jdbc.update("INSERT INTO task_executions(id,task_id,execution_id,status,started_at,completed_at,attempt_number,output_text,output_content_type) VALUES(?,?,?,'COMPLETE',now(),now(),0,'{}','application/json')", execution, task, execution);
        assertThat(Flyway.configure().dataSource(source).load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT execution_generation FROM tasks WHERE id=?", Integer.class, task)).isZero();
        assertThat(jdbc.queryForObject("SELECT execution_generation FROM task_executions WHERE id=?", Integer.class, execution)).isZero();
        assertThat(jdbc.queryForObject("SELECT execution_id FROM task_executions WHERE id=?", UUID.class, execution)).isEqualTo(execution);
        assertThat(jdbc.queryForObject("SELECT output_text FROM task_executions WHERE id=?", String.class, execution)).isEqualTo("{}");
        assertThatThrownBy(() -> jdbc.update("UPDATE tasks SET execution_generation=-1 WHERE id=?", task)).isInstanceOf(DataIntegrityViolationException.class);
        insert(jdbc, "{}", "task-queue", 0);
        assertThatThrownBy(() -> insert(jdbc, "x".repeat(32769), "task-queue", 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc, "界".repeat(11000), "task-queue", 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc, "{}", "arbitrary-topic", 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc, "{}", "task-queue", -1)).isInstanceOf(DataIntegrityViolationException.class);
    }
    private void insert(JdbcTemplate jdbc, String payload, String topic, int attempts) {
        jdbc.update("INSERT INTO outbox_events(id,dedupe_key,topic,event_key,event_type,payload_json,available_at,created_at,publish_attempts) VALUES(?,?,?,'key','TaskEvent',?,now(),now(),?)",
                UUID.randomUUID(), UUID.randomUUID().toString(), topic, payload, attempts);
    }
}
