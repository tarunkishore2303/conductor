package com.tarunkishore.loom_api;

import com.loom.common.dto.JobSubmitRequest;
import com.loom.common.dto.TaskDefinition;
import com.loom.common.event.TaskEvent;
import com.loom.common.model.FailurePolicy;
import com.loom.common.model.Job;
import com.loom.common.model.JobStatus;
import com.loom.common.model.Task;
import com.loom.common.model.TaskStatus;
import com.loom.common.outbox.JdbcOutboxWriter;
import com.tarunkishore.loom_api.repository.JobRepository;
import com.tarunkishore.loom_api.repository.TaskRepository;
import com.tarunkishore.loom_api.service.DlqService;
import com.tarunkishore.loom_api.service.JobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses real PostgreSQL transactions and locks, deliberately without Kafka or a relay. */
@SpringBootTest(properties = "conductor.outbox.enabled=false")
@Testcontainers
class JobOutboxIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg15").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JobService jobs;
    @Autowired DlqService dlq;
    @Autowired JobRepository jobRepository;
    @Autowired TaskRepository taskRepository;
    @Autowired JdbcOutboxWriter outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;

    private JobSubmitRequest request() {
        return new JobSubmitRequest("atomic-outbox-" + UUID.randomUUID(),
                List.of(new TaskDefinition("step", "NOOP", List.of(), 2)), FailurePolicy.FAIL_FAST);
    }

    private Task deadLettered() {
        Job job = jobs.submit(request());
        Task task = job.getTasks().getFirst();
        jdbc.update("update jobs set status='FAILED' where id=?", job.getId());
        jdbc.update("update tasks set status='DEAD_LETTERED',retry_count=2,dead_lettered_at=now() where id=?", task.getId());
        jdbc.update("insert into task_executions(id,task_id,execution_id,status,started_at,completed_at,attempt_number,execution_generation) "
                        + "values(?,?,?,'FAILED',now(),now(),0,0)", UUID.randomUUID(), task.getId(), UUID.randomUUID());
        return task;
    }

    @Test void committedSubmissionRetainsDurableIntentWithNoBroker() {
        Job job = jobs.submit(request());
        assertThat(jobRepository.findById(job.getId())).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where dedupe_key=? and published_at is null",
                Integer.class, "job-created:" + job.getId())).isEqualTo(1);
    }

    @Test void rolledBackSubmissionLeavesNeitherJobNorIntent() {
        AtomicReference<UUID> id = new AtomicReference<>();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            Job job = jobs.submit(request());
            id.set(job.getId());
            assertThat(jdbc.queryForObject("select count(*) from outbox_events where dedupe_key=?", Integer.class,
                    "job-created:" + job.getId())).isEqualTo(1);
            throw new IllegalStateException("simulate process failure before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jobRepository.findById(id.get())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where dedupe_key=?", Integer.class,
                "job-created:" + id.get())).isZero();
    }

    @Test void replayUsesFreshGenerationWithoutDiscardingHistoricalExecution() {
        Task task = deadLettered();
        transaction.executeWithoutResult(status -> outbox.taskQueue(new TaskEvent(task.getJob().getId(), task.getId(),
                task.getName(), 2, 0, 0), Instant.now()));
        Task replay = dlq.retryTask(task.getId());
        assertThat(replay.getExecutionGeneration()).isEqualTo(1);
        assertThat(replay.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(replay.getRetryCount()).isZero();
        assertThat(replay.getDeadLetteredAt()).isNull();
        assertThat(jobRepository.findById(task.getJob().getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where topic='task-queue' and event_key=?",
                Integer.class, task.getId().toString())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select payload_json from outbox_events where dedupe_key=?", String.class,
                "task-queue:" + task.getId() + ":1:0")).contains("\"generation\":1");
        assertThat(jdbc.queryForObject("select count(*) from task_executions where task_id=? and status='FAILED'",
                Integer.class, task.getId())).isEqualTo(1);
    }

    @Test void replayRollbackPreservesDeadLetterAndDoesNotLeaveDispatchIntent() {
        Task task = deadLettered();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            dlq.retryTask(task.getId());
            throw new IllegalStateException("rollback replay");
        })).isInstanceOf(IllegalStateException.class);
        Task unchanged = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(TaskStatus.DEAD_LETTERED);
        assertThat(unchanged.getExecutionGeneration()).isZero();
        assertThat(unchanged.getDeadLetteredAt()).isNotNull();
        assertThat(jobRepository.findById(task.getJob().getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_key=?", Integer.class,
                task.getId().toString())).isZero();
    }

    @Test void concurrentReplayRequestsCreateExactlyOneNewGenerationAndIntent() throws Exception {
        Task task = deadLettered();
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var calls = List.of(executor.submit(() -> replayAfterGate(task.getId(), gate)),
                    executor.submit(() -> replayAfterGate(task.getId(), gate)));
            gate.countDown();
            assertThat(List.of(calls.get(0).get(10, TimeUnit.SECONDS), calls.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("replayed", "conflict");
        }
        assertThat(taskRepository.findById(task.getId()).orElseThrow().getExecutionGeneration()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where dedupe_key=?", Integer.class,
                "task-queue:" + task.getId() + ":1:0")).isEqualTo(1);
    }

    private String replayAfterGate(UUID taskId, CountDownLatch gate) throws InterruptedException {
        gate.await();
        try { dlq.retryTask(taskId); return "replayed"; }
        catch (IllegalStateException expected) { return "conflict"; }
    }
}
