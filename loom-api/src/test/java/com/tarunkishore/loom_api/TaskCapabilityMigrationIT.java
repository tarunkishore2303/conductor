package com.tarunkishore.loom_api;

import com.loom.common.dto.TaskDefinition;
import com.loom.capability.TaskType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class TaskCapabilityMigrationIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg15").asCompatibleSubstituteFor("postgres"));

    @Test void upgradesExistingV7TasksAndTemplatesWithoutRewritingHistory() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).target("7").load().migrate();
        var jdbc = new JdbcTemplate(source);
        var job = UUID.randomUUID(); var task = UUID.randomUUID(); var template = UUID.randomUUID();
        jdbc.update("INSERT INTO jobs(id,name,status,failure_policy,created_at,updated_at) VALUES(?,?,'COMPLETE','FAIL_FAST',now(),now())", job,"legacy");
        jdbc.update("INSERT INTO tasks(id,job_id,name,status,max_retries,retry_count,created_at,updated_at) VALUES(?,?,?,'COMPLETE',2,0,now(),now())",task,job,"legacy-task");
        String legacyJson = "[{\"taskId\":\"a\",\"name\":\"legacy-task\",\"dependsOn\":[],\"maxRetries\":2}]";
        jdbc.update("INSERT INTO workflow_templates(id,name,dag_definition_json,default_failure_policy,created_at,updated_at) VALUES(?,? ,?,'FAIL_FAST',now(),now())",template,"legacy-template",legacyJson);
        var result = Flyway.configure().dataSource(source).load().migrate();
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT workflow_template_id FROM jobs WHERE id=?",UUID.class,job)).isNull();
        assertThat(jdbc.queryForObject("SELECT logical_id FROM tasks WHERE id=?",String.class,task)).isNull();
        assertThat(jdbc.queryForObject("SELECT task_type FROM tasks WHERE id=?",String.class,task)).isEqualTo("NOOP");
        assertThat(jdbc.queryForObject("SELECT task_config_json FROM tasks WHERE id=?",String.class,task)).isEqualTo("{}");
        String stored = jdbc.queryForObject("SELECT dag_definition_json FROM workflow_templates WHERE id=?",String.class,template);
        assertThat(stored).isEqualTo(legacyJson);
        var definitions = new JsonMapper().readValue(stored,TaskDefinition[].class);
        assertThat(definitions[0].type()).isEqualTo(TaskType.NOOP);
        assertThatThrownBy(() -> jdbc.update("UPDATE tasks SET task_type='SHELL' WHERE id=?",task))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE tasks SET task_config_json=? WHERE id=?","x".repeat(65537),task))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
