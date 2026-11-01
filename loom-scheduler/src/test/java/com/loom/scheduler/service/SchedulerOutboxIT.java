package com.loom.scheduler.service;

import com.loom.common.event.TaskEvent;
import com.loom.common.event.TaskResultEvent;
import com.loom.common.model.TaskStatus;
import com.loom.scheduler.repository.JobReactiveRepository;
import com.loom.scheduler.repository.TaskReactiveRepository;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.r2dbc.spi.ConnectionFactoryOptions.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL proves atomic reactive writes, dedupe and serialization of join evaluations. */
@Testcontainers
class SchedulerOutboxIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15-alpine");
    private static DatabaseClient database;
    private static DAGScheduler scheduler;
    private static TaskReactiveRepository tasks;
    private static JobReactiveRepository jobs;
    private static ReactiveOutboxWriter writer;
    private static TransactionalOperator transactions;

    @BeforeAll static void database() throws Exception {
        var factory = ConnectionFactories.get(ConnectionFactoryOptions.builder()
            .option(DRIVER, "postgresql").option(HOST, postgres.getHost())
            .option(PORT, postgres.getMappedPort(5432)).option(DATABASE, postgres.getDatabaseName())
            .option(USER, postgres.getUsername()).option(PASSWORD, postgres.getPassword()).build());
        database = DatabaseClient.create(factory);
        sql("""
            CREATE TABLE jobs (id UUID PRIMARY KEY, name TEXT NOT NULL, status TEXT NOT NULL,
                failure_policy TEXT NOT NULL, version BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ DEFAULT now());
            CREATE TABLE tasks (id UUID PRIMARY KEY, job_id UUID NOT NULL REFERENCES jobs(id), name TEXT NOT NULL,
                status TEXT NOT NULL, max_retries INT NOT NULL DEFAULT 2, retry_count INT NOT NULL DEFAULT 0,
                version BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ DEFAULT now());
            CREATE TABLE task_dependencies (task_id UUID REFERENCES tasks(id), depends_on_task_id UUID REFERENCES tasks(id),
                PRIMARY KEY(task_id, depends_on_task_id));
            CREATE TABLE task_executions (id UUID PRIMARY KEY, task_id UUID REFERENCES tasks(id), execution_id UUID UNIQUE,
                status TEXT NOT NULL);
            """);
        Path migration = Path.of("../loom-api/src/main/resources/db/migration/V12__transactional_outbox.sql");
        if (!Files.exists(migration)) migration = Path.of("loom-api/src/main/resources/db/migration/V12__transactional_outbox.sql");
        sql(Files.readString(migration));
        var repositories = new R2dbcRepositoryFactory(new R2dbcEntityTemplate(factory));
        tasks = repositories.getRepository(TaskReactiveRepository.class);
        jobs = repositories.getRepository(JobReactiveRepository.class);
        writer = new ReactiveOutboxWriter(database);
        transactions = TransactionalOperator.create(new R2dbcTransactionManager(factory));
        scheduler = new DAGScheduler(tasks, jobs, writer, transactions);
    }

    @BeforeEach void clear() {
        sql("TRUNCATE outbox_events,task_dependencies,task_executions,tasks,jobs CASCADE");
    }

    @Test void schedulingCommitsStateAndOneIntentDespiteRepeatedInput() {
        UUID job = job(), task = task(job, "PENDING", 0);
        scheduler.scheduleJob(job).block(Duration.ofSeconds(10));
        scheduler.scheduleJob(job).block(Duration.ofSeconds(10));
        assertThat(status(job)).isEqualTo("RUNNING");
        assertThat(count("SELECT count(*) FROM outbox_events")).isEqualTo(1);
        String payload = database.sql("SELECT payload_json FROM outbox_events").map((row, meta) -> row.get(0, String.class)).one().block();
        assertThat(payload).contains(task.toString(), "\"generation\":0").doesNotContain("output", "config");
    }

    @Test void failingOutboxInsertRollsBackCreatedToRunningTransition() {
        UUID job = job(); task(job, "PENDING", 0);
        var broken = mock(ReactiveOutboxWriter.class);
        when(broken.taskQueue(any())).thenReturn(Mono.error(new IllegalStateException("forced insert failure")));
        var failing = new DAGScheduler(tasks, jobs, broken, transactions);
        assertThatThrownBy(() -> failing.scheduleJob(job).block(Duration.ofSeconds(10)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(status(job)).isEqualTo("CREATED");
        assertThat(count("SELECT count(*) FROM outbox_events")).isZero();
    }

    @Test void concurrentBranchResultsDispatchJoinOnce() {
        UUID job = job(), left = task(job, "COMPLETE", 0), right = task(job, "COMPLETE", 0), join = task(job, "PENDING", 0);
        sql("UPDATE jobs SET status='RUNNING'");
        dependency(join, left); dependency(join, right);
        var first = completion(job, left, 0);
        var second = completion(job, right, 0);
        CompletableFuture.allOf(scheduler.onTaskResult(first).toFuture(), scheduler.onTaskResult(second).toFuture())
            .orTimeout(15, TimeUnit.SECONDS).join();
        assertThat(count("SELECT count(*) FROM outbox_events")).isEqualTo(1);
        assertThat(status(job)).isEqualTo("RUNNING");
        assertThat(database.sql("SELECT dedupe_key FROM outbox_events").map((row, meta) -> row.get(0, String.class)).one().block())
            .isEqualTo("task-queue:" + join + ":0:0");
    }

    @Test void staleGenerationResultCannotResurrectOrDispatch() {
        UUID job = job(), source = task(job, "COMPLETE", 1), next = task(job, "PENDING", 0);
        sql("UPDATE jobs SET status='RUNNING'"); dependency(next, source);
        scheduler.onTaskResult(completion(job, source, 0)).block(Duration.ofSeconds(10));
        assertThat(count("SELECT count(*) FROM outbox_events")).isZero();
        assertThat(status(job)).isEqualTo("RUNNING");
    }

    @Test void cancelledJobIgnoresDueResult() {
        UUID job = job(), source = task(job, "COMPLETE", 0), next = task(job, "PENDING", 0);
        sql("UPDATE jobs SET status='CANCELLED'"); dependency(next, source);
        scheduler.onTaskResult(completion(job, source, 0)).block(Duration.ofSeconds(10));
        assertThat(count("SELECT count(*) FROM outbox_events")).isZero();
        assertThat(status(job)).isEqualTo("CANCELLED");
    }

    @Test void duplicateDispatchUsesOnConflictInsteadOfPoisoningTransaction() {
        UUID job = job(), task = task(job, "PENDING", 2);
        var event = new TaskEvent(job, task, "task", 2, 0, 2);
        transactions.transactional(writer.taskQueue(event).then(writer.taskQueue(event))).block(Duration.ofSeconds(10));
        assertThat(count("SELECT count(*) FROM outbox_events")).isEqualTo(1);
    }

    @Test void writerRequiresTransaction() {
        UUID job = job(), task = task(job, "PENDING", 0);
        var event = new TaskEvent(job, task, "task", 2, 0, 0);
        assertThatThrownBy(() -> writer.taskQueue(event).block(Duration.ofSeconds(10))).isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT count(*) FROM outbox_events")).isZero();
    }

    @Test void failFastCancelsRetryCommittedWhileWaitingForWorkerRowLock() {
        UUID job = job(), failed = task(job, "DEAD_LETTERED", 0), retry = task(job, "PENDING", 0);
        sql("UPDATE jobs SET status='RUNNING'");
        UUID execution = UUID.randomUUID();
        database.sql("INSERT INTO task_executions(id,task_id,execution_id,status,execution_generation) VALUES(:id,:task,:execution,'FAILED',0)")
            .bind("id", UUID.randomUUID()).bind("task", failed).bind("execution", execution).fetch().rowsUpdated().block();
        var event = new TaskResultEvent(job, failed, execution, TaskStatus.DEAD_LETTERED, null);
        var locked = Sinks.<Void>one();
        var release = Sinks.<Void>one();
        // Hold the same task row that a real worker holds during execution, then commit
        // another PENDING retry with a newer version while FAIL_FAST is waiting on it.
        var worker = transactions.transactional(
            database.sql("UPDATE tasks SET status='RUNNING',version=version+1 WHERE id=:id")
                .bind("id", retry).fetch().rowsUpdated()
                .doOnSuccess(ignored -> locked.tryEmitEmpty())
                .then(release.asMono())
                .then(database.sql("UPDATE tasks SET status='PENDING',version=version+1 WHERE id=:id")
                    .bind("id", retry).fetch().rowsUpdated()).then()).toFuture();
        CompletableFuture<Void> cancellation = null;
        try {
            locked.asMono().block(Duration.ofSeconds(5));
            cancellation = scheduler.onTaskResult(event).toFuture();
            // Observe the actual PostgreSQL lock wait instead of relying on a timing sleep.
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            long waiters = 0;
            while (waiters == 0 && System.nanoTime() < deadline) {
                waiters = count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%cancel-pending-tasks%'");
            }
            assertThat(waiters).isPositive();
        } finally {
            release.tryEmitEmpty();
            worker.orTimeout(10, TimeUnit.SECONDS).join();
            if (cancellation != null) cancellation.orTimeout(10, TimeUnit.SECONDS).join();
        }
        assertThat(status(job)).isEqualTo("FAILED");
        assertThat(database.sql("SELECT status FROM tasks WHERE id=:id").bind("id", retry)
            .map((row, meta) -> row.get(0, String.class)).one().block()).isEqualTo("CANCELLED");
    }

    private static TaskResultEvent completion(UUID job, UUID task, int generation) {
        UUID execution = UUID.randomUUID();
        database.sql("INSERT INTO task_executions(id,task_id,execution_id,status,execution_generation) VALUES(:id,:task,:execution,'COMPLETE',:generation)")
            .bind("id", UUID.randomUUID()).bind("task", task).bind("execution", execution).bind("generation", generation)
            .fetch().rowsUpdated().block();
        return new TaskResultEvent(job, task, execution, TaskStatus.COMPLETE, null);
    }
    private static UUID job() {
        UUID id = UUID.randomUUID();
        database.sql("INSERT INTO jobs(id,name,status,failure_policy) VALUES(:id,'test','CREATED','FAIL_FAST')")
            .bind("id", id).fetch().rowsUpdated().block(); return id;
    }
    private static UUID task(UUID job, String status, int generation) {
        UUID id = UUID.randomUUID();
        database.sql("INSERT INTO tasks(id,job_id,name,status,execution_generation) VALUES(:id,:job,'task',:status,:generation)")
            .bind("id", id).bind("job", job).bind("status", status).bind("generation", generation).fetch().rowsUpdated().block(); return id;
    }
    private static void dependency(UUID task, UUID source) {
        database.sql("INSERT INTO task_dependencies VALUES(:task,:source)").bind("task", task).bind("source", source).fetch().rowsUpdated().block();
    }
    private static String status(UUID job) {
        return database.sql("SELECT status FROM jobs WHERE id=:id").bind("id", job).map((row, meta) -> row.get(0, String.class)).one().block();
    }
    private static long count(String query) {
        return database.sql(query).map((row, meta) -> row.get(0, Long.class)).one().block();
    }
    private static void sql(String query) {
        database.sql(query).fetch().rowsUpdated().block(Duration.ofSeconds(10));
    }
}
