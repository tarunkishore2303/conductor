package com.loom.worker;

import com.loom.common.event.TaskEvent;
import com.loom.common.event.TaskResultEvent;
import com.loom.common.model.TaskStatus;
import com.loom.worker.repository.WorkerTaskRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class TaskExecutionIT {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15")
        .withDatabaseName("loom")
        .withUsername("loom")
        .withPassword("loom");

    @Container
    static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.5.3"));

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
        DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // Use Hibernate DDL instead of Flyway (worker has no migration scripts)
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("conductor.demo.failures-enabled", () -> "true");
    }

    @Autowired WorkerTaskRepository taskRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;

    private KafkaConsumer<String, TaskResultEvent> resultConsumer;

    @AfterEach
    void cleanup() {
        if (resultConsumer != null) resultConsumer.close();
    }

    private UUID insertJobAndTask(String taskName) {
        UUID jobId  = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        jdbc.update("""
            INSERT INTO jobs (id, name, status, failure_policy, created_at, updated_at, version)
            VALUES (?, ?, 'RUNNING', 'FAIL_FAST', ?, ?, 0)
            """, jobId, "it-job", now, now);

        jdbc.update("""
            INSERT INTO tasks (id, job_id, name, status, max_retries, retry_count, created_at, updated_at, version)
            VALUES (?, ?, ?, 'PENDING', 3, 0, ?, ?, 0)
            """, taskId, jobId, taskName, now, now);

        return taskId;
    }

    private KafkaConsumer<String, TaskResultEvent> buildResultConsumer() {
        KafkaConsumer<String, TaskResultEvent> consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "it-result-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class.getName(),
            JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.loom.common.event",
            JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, TaskResultEvent.class.getName(),
            JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, "false"
        ));
        consumer.subscribe(List.of("task-results"));
        return consumer;
    }

    @Test
    void taskExecutesSuccessfully_updatesStatusToComplete() {
        UUID taskId = insertJobAndTask("step-compute");

        kafkaTemplate.send("task-queue", taskId.toString(),
            new TaskEvent(UUID.randomUUID(), taskId, "step-compute", 3, 0));

        Awaitility.await()
            .atMost(20, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> {
                var task = taskRepo.findById(taskId).orElseThrow();
                assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETE);
            });
    }

    @Test
    void taskExecutesSuccessfully_publishesCompleteResultEvent() {
        resultConsumer = buildResultConsumer();
        UUID taskId = insertJobAndTask("step-publish-test");
        UUID jobId  = UUID.fromString(jdbc.queryForObject(
            "SELECT job_id FROM tasks WHERE id = ?", String.class, taskId));

        kafkaTemplate.send("task-queue", taskId.toString(),
            new TaskEvent(jobId, taskId, "step-publish-test", 3, 0));

        Awaitility.await()
            .atMost(20, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ConsumerRecords<String, TaskResultEvent> records =
                    resultConsumer.poll(Duration.ofMillis(500));
                boolean found = false;
                for (var rec : records) {
                    if (taskId.toString().equals(rec.key())
                            && rec.value().status() == TaskStatus.COMPLETE) {
                        found = true;
                        break;
                    }
                }
                assertThat(found).as("COMPLETE result event not found for task " + taskId).isTrue();
            });
    }

    @Test
    void duplicateTaskEvent_idempotent_doesNotRunTwice() {
        UUID taskId = insertJobAndTask("step-idempotent");
        UUID jobId  = UUID.fromString(jdbc.queryForObject(
            "SELECT job_id FROM tasks WHERE id = ?", String.class, taskId));

        TaskEvent event = new TaskEvent(jobId, taskId, "step-idempotent", 3, 0);

        // Send same event twice (same attemptNumber → same executionId)
        kafkaTemplate.send("task-queue", taskId.toString(), event);
        kafkaTemplate.send("task-queue", taskId.toString(), event);

        Awaitility.await()
            .atMost(20, TimeUnit.SECONDS)
            .untilAsserted(() ->
                assertThat(taskRepo.findById(taskId).orElseThrow().getStatus())
                    .isEqualTo(TaskStatus.COMPLETE));

        // Exactly one TaskExecution record
        int count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM task_executions WHERE task_id = ?", Integer.class, taskId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void failedDemoTaskPersistsAttemptTimelineAndDeadLetterEvidenceBeforeResult() {
        resultConsumer = buildResultConsumer();
        UUID taskId = insertJobAndTask("demoConnectionTimeout");
        jdbc.update("UPDATE tasks SET max_retries = 2 WHERE id = ?", taskId);
        UUID jobId = UUID.fromString(jdbc.queryForObject(
                "SELECT job_id FROM tasks WHERE id = ?", String.class, taskId));
        kafkaTemplate.send("task-queue", taskId.toString(),
                new TaskEvent(jobId, taskId, "demoConnectionTimeout", 2, 0));

        Awaitility.await().atMost(25, TimeUnit.SECONDS).untilAsserted(() -> {
            ConsumerRecords<String, TaskResultEvent> records = resultConsumer.poll(Duration.ofMillis(500));
            boolean found = false;
            for (var record : records) {
                if (taskId.toString().equals(record.key())
                        && record.value().status() == TaskStatus.DEAD_LETTERED) {
                    // The Kafka result is emitted after the evidence transaction commits.
                    var task = taskRepo.findById(taskId).orElseThrow();
                    assertThat(task.getStatus()).isEqualTo(TaskStatus.DEAD_LETTERED);
                    assertThat(task.getRetryCount()).isEqualTo(2);
                    assertThat(task.getDeadLetteredAt()).isNotNull();
                    assertThat(record.value().errorMessage()).isEqualTo("Demo dependency connection timed out");
                    found = true;
                }
            }
            assertThat(found).isTrue();
        });
        assertThat(jdbc.queryForList(
                "SELECT attempt_number FROM task_executions WHERE task_id = ? ORDER BY attempt_number",
                Integer.class, taskId)).containsExactly(0, 1, 2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM task_executions WHERE task_id = ? AND error_message IS NOT NULL "
                        + "AND error_type = 'java.net.ConnectException' AND completed_at IS NOT NULL",
                Integer.class, taskId)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM task_executions WHERE task_id = ? AND retry_scheduled_at IS NOT NULL",
                Integer.class, taskId)).isEqualTo(2);
    }
}
