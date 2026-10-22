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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import com.loom.capability.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class TaskExecutionIT {

    private static HttpServer http;
    private static java.util.concurrent.ExecutorService httpExecutor;
    private static final Map<String, String> notifications = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> flakyAttempts = new ConcurrentHashMap<>();

    @BeforeAll static void startHttpEndpoint() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        http.setExecutor(httpExecutor);
        http.createContext("/healthy", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write(new byte[] {'o', 'k'});
            exchange.close();
        });
        http.createContext("/unavailable", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        http.createContext("/orders", exchange -> {
            byte[] body = "{\"id\":42,\"email\":\"demo@example.test\",\"internal\":\"omit\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        http.createContext("/notify", exchange -> {
            byte[] body = exchange.getRequestBody().readNBytes(65537);
            if (body.length > 65536) { exchange.sendResponseHeaders(413, -1); exchange.close(); return; }
            notifications.put(exchange.getRequestURI().getPath().substring("/notify/".length()), new String(body, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        http.createContext("/flaky", exchange -> {
            int attempt = flakyAttempts.computeIfAbsent(exchange.getRequestURI().getPath().substring("/flaky/".length()), ignored -> new AtomicInteger()).incrementAndGet();
            if (attempt <= 2) { exchange.sendResponseHeaders(500, -1); exchange.close(); return; }
            byte[] body = "{\"id\":42}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        http.start();
    }

    @AfterAll static void stopHttpEndpoint() {
        if (http != null) http.stop(0);
        if (httpExecutor != null) httpExecutor.shutdownNow();
    }

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
        registry.add("conductor.tasks.http.allow-private-addresses", () -> "true");
    }

    @Autowired WorkerTaskRepository taskRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired TaskConfigCodec configCodec;

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
            new TaskEvent(jobId(taskId), taskId, "step-compute", 3, 0));

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

    @Test
    void realHttpTaskCompletesUsingPersistedCapabilityConfiguration() {
        var config = new HttpRequestTaskConfig("GET", localUrl("/healthy"), 2000, null, null);
        UUID taskId = insertCapabilityTask("real-http", TaskType.HTTP_REQUEST, config, 0);
        dispatch(taskId, "real-http", 0);
        awaitComplete(taskId);
        var task = taskRepo.findById(taskId).orElseThrow();
        assertThat(task.getTaskType()).isEqualTo(TaskType.HTTP_REQUEST);
        assertThat(configCodec.decode(task.getTaskType(), task.getTaskConfigJson())).isEqualTo(config);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_executions WHERE task_id = ? AND status = 'COMPLETE'", Integer.class, taskId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT output_text FROM task_executions WHERE task_id = ?", String.class, taskId)).isNull();
    }

    @Test
    void realHttpFailureRetriesThreeAttemptsAndCommitsDlqEvidenceBeforeKafkaResult() {
        resultConsumer = buildResultConsumer();
        UUID taskId = insertCapabilityTask("real-http-failure", TaskType.HTTP_REQUEST,
                new HttpRequestTaskConfig("GET", localUrl("/unavailable"), 2000, null, null), 2);
        dispatch(taskId, "real-http-failure", 2);
        Awaitility.await().atMost(25, TimeUnit.SECONDS).untilAsserted(() -> {
            var records = resultConsumer.poll(Duration.ofMillis(500));
            boolean found = false;
            for (var record : records) {
                if (taskId.toString().equals(record.key()) && record.value().status() == TaskStatus.DEAD_LETTERED) {
                    // Receipt proves the observable result follows committed execution evidence.
                    var task = taskRepo.findById(taskId).orElseThrow();
                    assertThat(task.getStatus()).isEqualTo(TaskStatus.DEAD_LETTERED);
                    assertThat(task.getRetryCount()).isEqualTo(2);
                    assertThat(task.getDeadLetteredAt()).isNotNull();
                    assertThat(record.value().errorMessage()).isEqualTo("HTTP endpoint returned status 500");
                    assertThat(jdbc.queryForList("SELECT attempt_number FROM task_executions WHERE task_id = ? ORDER BY attempt_number", Integer.class, taskId))
                            .containsExactly(0, 1, 2);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM task_executions WHERE task_id = ? AND error_type = 'HTTP_STATUS_500' AND error_message = 'HTTP endpoint returned status 500' AND completed_at IS NOT NULL", Integer.class, taskId)).isEqualTo(3);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM task_executions WHERE task_id = ? AND retry_scheduled_at IS NOT NULL", Integer.class, taskId)).isEqualTo(2);
                    found = true;
                }
            }
            assertThat(found).isTrue();
        });
    }

    @Test
    void delayCapabilityExecutesThroughKafkaWorkerRegistry() {
        UUID taskId = insertCapabilityTask("real-delay", TaskType.DELAY, new DelayTaskConfig(1), 0);
        dispatch(taskId, "real-delay", 0);
        awaitComplete(taskId);
    }

    @Test
    void staticTransformCapabilityExecutesThroughKafkaWorkerRegistry() {
        UUID taskId = insertCapabilityTask("real-transform", TaskType.TRANSFORM,
                new TransformTaskConfig("JSON_SELECT", "{\"id\":42,\"internal\":\"omitted\"}", List.of("id"), Map.of()), 0);
        dispatch(taskId, "real-transform", 0);
        awaitComplete(taskId);
        assertThat(jdbc.queryForObject("SELECT output_text FROM task_executions WHERE task_id = ?", String.class, taskId)).isEqualTo("{\"id\":42}");
    }

    @Test
    void committedSuccessfulOutputsFlowThroughKafkaDispatchedHttpTransformPostPipeline() {
        String notification = UUID.randomUUID().toString();
        UUID fetch = insertCapabilityTask("fetch", TaskType.HTTP_REQUEST,
                new HttpRequestTaskConfig("GET", localUrl("/orders"), 2000, null, null, true, null), 0);
        UUID transform = insertLinkedTask(fetch, "select", TaskType.TRANSFORM,
                new TransformTaskConfig("JSON_SELECT", null, List.of("id", "email"), Map.of(), "fetch"), 0);
        UUID post = insertLinkedTask(transform, "notify", TaskType.HTTP_REQUEST,
                new HttpRequestTaskConfig("POST", localUrl("/notify/" + notification), 2000, "application/json", null, false, "select"), 0);
        // Scheduler is outside this worker fixture: dispatch only after observing the previous committed completion.
        dispatch(fetch, "fetch", 0); awaitComplete(fetch);
        assertThat(output(fetch)).contains("\"internal\":\"omit\"");
        dispatch(transform, "select", 0); awaitComplete(transform);
        String selected = "{\"id\":42,\"email\":\"demo@example.test\"}";
        assertThat(output(transform)).isEqualTo(selected);
        dispatch(post, "notify", 0); awaitComplete(post);
        assertThat(notifications.remove(notification)).isEqualTo(selected);
        assertThat(output(post)).isNull();
        assertThat(jdbc.queryForList("SELECT output_content_type FROM task_executions WHERE task_id IN (?,?) ORDER BY task_id", String.class, fetch, transform))
                .containsExactly("application/json", "application/json");
    }

    @Test
    void upstreamRetrySuccessExposesOnlySuccessfulAttemptOutputToTransform() {
        String fixtureId = UUID.randomUUID().toString();
        UUID fetch = insertCapabilityTask("fetch", TaskType.HTTP_REQUEST,
                new HttpRequestTaskConfig("GET", localUrl("/flaky/" + fixtureId), 2000, null, null, true, null), 2);
        UUID transform = insertLinkedTask(fetch, "select", TaskType.TRANSFORM,
                new TransformTaskConfig("JSON_SELECT", null, List.of("id"), Map.of(), "fetch"), 0);
        dispatch(fetch, "fetch", 2); awaitComplete(fetch);
        assertThat(jdbc.queryForList("SELECT status FROM task_executions WHERE task_id=? ORDER BY attempt_number", String.class, fetch))
                .containsExactly("FAILED", "FAILED", "COMPLETE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_executions WHERE task_id=? AND status='FAILED' AND output_text IS NULL AND output_content_type IS NULL", Integer.class, fetch)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT output_text FROM task_executions WHERE task_id=? AND status='COMPLETE'", String.class, fetch)).isEqualTo("{\"id\":42}");
        dispatch(transform, "select", 0); awaitComplete(transform);
        assertThat(output(transform)).isEqualTo("{\"id\":42}");
        assertThat(flakyAttempts.remove(fixtureId).get()).isEqualTo(3);
    }

    @Test
    void failedUpstreamHasNoOutputAndDependentRemainsUndispatched() {
        UUID fetch = insertCapabilityTask("fetch", TaskType.HTTP_REQUEST,
                new HttpRequestTaskConfig("GET", localUrl("/unavailable"), 2000, null, null, true, null), 2);
        UUID transform = insertLinkedTask(fetch, "select", TaskType.TRANSFORM,
                new TransformTaskConfig("JSON_SELECT", null, List.of("id"), Map.of(), "fetch"), 0);
        dispatch(fetch, "fetch", 2);
        Awaitility.await().atMost(25, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(taskRepo.findById(fetch).orElseThrow().getStatus()).isEqualTo(TaskStatus.DEAD_LETTERED));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_executions WHERE task_id=? AND output_text IS NULL AND output_content_type IS NULL", Integer.class, fetch)).isEqualTo(3);
        assertThat(taskRepo.findById(transform).orElseThrow().getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_executions WHERE task_id=?", Integer.class, transform)).isZero();
        // This proves worker persistence, not scheduler behavior: no downstream event was dispatched here.
    }

    private UUID insertLinkedTask(UUID upstream, String logicalId, TaskType type, TaskConfig config, int retries) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tasks(id,job_id,name,logical_id,task_type,task_config_json,status,max_retries,retry_count,created_at,updated_at,version) VALUES(?,?,?,?,?,?,'PENDING',?,0,now(),now(),0)",
                id, jobId(upstream), logicalId, logicalId, type.name(), configCodec.encode(config), retries);
        jdbc.update("INSERT INTO task_dependencies(task_id,depends_on_task_id) VALUES(?,?)", id, upstream);
        return id;
    }

    private UUID jobId(UUID taskId) {
        return UUID.fromString(jdbc.queryForObject("SELECT job_id FROM tasks WHERE id=?", String.class, taskId));
    }

    private String output(UUID taskId) {
        return jdbc.queryForObject("SELECT output_text FROM task_executions WHERE task_id=? AND status='COMPLETE'", String.class, taskId);
    }

    private UUID insertCapabilityTask(String name, TaskType type, TaskConfig config, int maxRetries) {
        UUID taskId = insertJobAndTask(name);
        jdbc.update("UPDATE tasks SET logical_id = ?, task_type = ?, task_config_json = ?, max_retries = ? WHERE id = ?",
                name, type.name(), configCodec.encode(config), maxRetries, taskId);
        return taskId;
    }

    private void dispatch(UUID taskId, String name, int maxRetries) {
        UUID jobId = UUID.fromString(jdbc.queryForObject("SELECT job_id FROM tasks WHERE id = ?", String.class, taskId));
        kafkaTemplate.send("task-queue", taskId.toString(), new TaskEvent(jobId, taskId, name, maxRetries, 0));
    }

    private void awaitComplete(UUID taskId) {
        Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(taskRepo.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.COMPLETE));
    }

    private static String localUrl(String path) { return "http://127.0.0.1:" + http.getAddress().getPort() + path; }
}
