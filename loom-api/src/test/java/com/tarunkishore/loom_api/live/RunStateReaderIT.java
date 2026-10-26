package com.tarunkishore.loom_api.live;

import com.loom.common.model.JobStatus;
import com.loom.common.model.TaskStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RunStateReaderIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg15").asCompatibleSubstituteFor("postgres"));
    static DriverManagerDataSource source;

    @BeforeAll static void schema() {
        source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
    }

    @Test void observerCannotPublishUncommittedOrRolledBackStatesButSeesCommittedOutputBlindMetadata() {
        var jdbc = new JdbcTemplate(source);
        var transactions = new DataSourceTransactionManager(source);
        var reader = reader(transactions);
        var writer = new TransactionTemplate(transactions);
        var publisher = new RunEventPublisher();
        UUID job = UUID.randomUUID(), task = UUID.randomUUID();
        jdbc.update("INSERT INTO jobs(id,name,status,failure_policy,created_at,updated_at) VALUES(?,'stream','RUNNING','FAIL_FAST',now(),now())", job);
        jdbc.update("INSERT INTO tasks(id,job_id,name,status,max_retries,retry_count,created_at,updated_at) VALUES(?,?,'secret-config-name','PENDING',2,0,now(),now())", task, job);
        var initial = reader.read(List.of(job)).get(job);
        writer.executeWithoutResult(status -> {
            jdbc.update("UPDATE jobs SET status='FAILED' WHERE id=?", job);
            assertThat(publisher.changes(initial, reader.read(List.of(job)).get(job))).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(publisher.changes(initial, reader.read(List.of(job)).get(job))).isEmpty();
        writer.executeWithoutResult(status -> {
            jdbc.update("UPDATE tasks SET status='COMPLETE' WHERE id=?", task);
            jdbc.update("UPDATE jobs SET status='COMPLETE' WHERE id=?", job);
            assertThat(publisher.changes(initial, reader.read(List.of(job)).get(job))).isEmpty();
        });
        // Persist sensitive attempt output/error evidence. None of these columns are read by the stream.
        jdbc.update("""
                INSERT INTO task_executions(id,task_id,execution_id,status,started_at,completed_at,attempt_number,
                  output_text,output_content_type,error_message)
                VALUES(?,?,?,'COMPLETE',now(),now(),1,'private-output-token','text/plain','private-error-token')
                """, UUID.randomUUID(), task, UUID.randomUUID());
        var committed = reader.read(List.of(job)).get(job);
        assertThat(committed.status()).isEqualTo(JobStatus.COMPLETE);
        assertThat(committed.tasks().get(task).status()).isEqualTo(TaskStatus.COMPLETE);
        assertThat(publisher.changes(initial, committed)).extracting(RunEvent::type)
                .containsExactly(RunEvent.Type.TASK_COMPLETED, RunEvent.Type.JOB_COMPLETED);
        assertThat(committed.toString()).doesNotContain("private-output-token", "private-error-token", "secret-config-name");
    }

    @Test void retryEvidenceIncludesFirstAttemptWithoutAnIncrementedTaskCounter() {
        var jdbc = new JdbcTemplate(source);
        var reader = new RunStateReader(source, RunEventStreamTest.properties(2));
        UUID job = UUID.randomUUID(), task = UUID.randomUUID();
        jdbc.update("INSERT INTO jobs(id,name,status,failure_policy,created_at,updated_at) VALUES(?,'retry-stream','RUNNING','FAIL_FAST',now(),now())", job);
        jdbc.update("INSERT INTO tasks(id,job_id,name,status,max_retries,retry_count,created_at,updated_at) VALUES(?,?,'retry','PENDING',2,0,now(),now())", task, job);
        var initial = reader.read(List.of(job)).get(job);
        jdbc.update("""
                INSERT INTO task_executions(id,task_id,execution_id,status,started_at,completed_at,attempt_number,retry_scheduled_at)
                VALUES(?,?,?,'FAILED',now(),now(),0,now()+interval '1 second')
                """, UUID.randomUUID(), task, UUID.randomUUID());
        assertThat(new RunEventPublisher().changes(initial, reader.read(List.of(job)).get(job)))
                .singleElement().satisfies(event -> assertThat(event.type()).isEqualTo(RunEvent.Type.TASK_RETRY_SCHEDULED));
    }

    private static RunStateReader reader(DataSourceTransactionManager manager) {
        var proxy = new ProxyFactory(new RunStateReader(source, RunEventStreamTest.properties(2)));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (RunStateReader) proxy.getProxy();
    }
}
