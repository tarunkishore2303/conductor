package com.tarunkishore.loom_api.outbox;

import com.loom.common.event.*;
import com.loom.common.model.TaskStatus;
import com.loom.common.outbox.JdbcOutboxWriter;
import com.loom.common.outbox.OutboxMessages;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "conductor.outbox.enabled=false")
@Testcontainers
class OutboxRelayIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg15").asCompatibleSubstituteFor("postgres"));
    @Container static final ConfluentKafkaContainer kafkaContainer = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.7");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcOutboxWriter writer;
    @Autowired OutboxStore store;
    @Autowired TransactionTemplate transactions;
    @Autowired KafkaTemplate<String, byte[]> kafka;
    final OutboxProperties properties = new OutboxProperties(true, Duration.ofMillis(500), 50, Duration.ofSeconds(30), Duration.ofSeconds(10));

    @BeforeEach void clean() { jdbc.update("DELETE FROM outbox_events"); }
    UUID insert(Instant due) {
        var message = OutboxMessages.taskQueue(new TaskEvent(UUID.randomUUID(), UUID.randomUUID(), "bounded", 2, 1), due);
        transactions.executeWithoutResult(status -> writer.insert(message));
        return message.id();
    }
    @Test void futureIntentSurvivesButCannotBeClaimed() {
        insert(Instant.now().plusSeconds(3600));
        assertThat(store.pendingCount()).isEqualTo(1);
        assertThat(store.claim(Duration.ofSeconds(30))).isEmpty();
    }
    @Test void insertionMustShareAStateTransactionAndRollbackTogether() {
        assertThatThrownBy(() -> writer.jobCreated(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        transactions.executeWithoutResult(status -> { writer.jobCreated(UUID.randomUUID()); status.setRollbackOnly(); });
        assertThat(store.pendingCount()).isZero();
    }
    @Test void duplicateIntentDoesNotCreateAnotherRow() {
        UUID job = UUID.randomUUID();
        transactions.executeWithoutResult(status -> { writer.jobCreated(job); writer.jobCreated(job); });
        assertThat(store.pendingCount()).isEqualTo(1);
    }
    @Test void dueClaimIsExclusiveAndSuccessMarksPublished() {
        insert(Instant.now());
        var claim = store.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(store.claim(Duration.ofSeconds(30))).isEmpty();
        assertThat(store.published(claim)).isTrue();
        assertThat(store.pendingCount()).isZero();
    }
    @Test void concurrentRelaysSkipLockedRows() throws Exception {
        UUID id = insert(Instant.now());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> store.claim(Duration.ofSeconds(30)));
            var second = pool.submit(() -> store.claim(Duration.ofSeconds(30)));
            assertThat(List.of(first.get(), second.get()).stream().flatMap(Optional::stream).map(OutboxStore.Claim::id).toList())
                    .containsExactly(id);
        }
    }
    @Test void aLockedDueRowDoesNotBlockAnotherRelay() throws Exception {
        UUID locked = insert(Instant.now().minusSeconds(2));
        UUID available = insert(Instant.now().minusSeconds(1));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = pool.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM outbox_events WHERE id=? FOR UPDATE", locked);
                held.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lock test timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                var claimed = pool.submit(() -> store.claim(Duration.ofSeconds(30))).get(2, TimeUnit.SECONDS);
                assertThat(claimed.orElseThrow().id()).isEqualTo(available);
            } finally { release.countDown(); }
            holder.get(5, TimeUnit.SECONDS);
        }
    }
    @Test void relayRejectsAnyCallerDatabaseTransaction() {
        var relay = new OutboxRelay(store, kafka, properties, new SimpleMeterRegistry());
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> relay.poll()))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void expiredLeaseRecoversAndOldOwnerCannotMarkIt() {
        UUID id = insert(Instant.now());
        var abandoned = store.claim(Duration.ofSeconds(30)).orElseThrow();
        jdbc.update("UPDATE outbox_events SET claimed_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?", id);
        var recovered = store.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(recovered.token()).isNotEqualTo(abandoned.token());
        assertThat(store.published(abandoned)).isFalse();
        assertThat(store.published(recovered)).isTrue();
        assertThat(recovered.attempts()).isEqualTo(2);
    }
    @Test void failedSendIsRetainedWithBoundedRetryAndSafeMetrics() {
        insert(Instant.now());
        @SuppressWarnings("unchecked") KafkaTemplate<String, byte[]> unavailable = mock(KafkaTemplate.class);
        when(unavailable.send(anyString(), anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sensitive broker error")));
        var metrics = new SimpleMeterRegistry();
        new OutboxRelay(store, unavailable, properties, metrics).poll();
        assertThat(store.pendingCount()).isEqualTo(1);
        assertThat(store.claim(Duration.ofSeconds(30))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT publish_attempts FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT claimed_by FROM outbox_events", String.class)).isNull();
        assertThat(metrics.counter("conductor.outbox.publish.failure").count()).isEqualTo(1);
        assertThat(metrics.getMeters().stream().flatMap(m -> m.getId().getTags().stream()).map(t -> t.getKey()).toList())
                .doesNotContain("jobId", "taskId", "error", "eventId");
    }
    @Test void noDatabaseTransactionIsHeldDuringSend() {
        insert(Instant.now());
        @SuppressWarnings("unchecked") KafkaTemplate<String, byte[]> mocked = mock(KafkaTemplate.class);
        when(mocked.send(anyString(), anyString(), any(byte[].class))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return CompletableFuture.completedFuture(null);
        });
        new OutboxRelay(store, mocked, properties, new SimpleMeterRegistry()).poll();
        assertThat(store.pendingCount()).isZero();
    }
    @Test void rawJsonWorksWithExistingConsumersAndNoJavaTypeHeaders() {
        UUID job = UUID.randomUUID(), task = UUID.randomUUID(), execution = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            writer.jobCreated(job);
            writer.taskQueue(new TaskEvent(job, task, "bounded", 2, 1, 3), Instant.now());
            writer.taskResult(new TaskResultEvent(job, task, execution, TaskStatus.COMPLETE, null));
        });
        new OutboxRelay(store, kafka, properties, new SimpleMeterRegistry()).poll();
        assertThat(store.pendingCount()).isZero();
        try (var consumer = consumer()) {
            consumer.subscribe(List.of("job-created", "task-queue", "task-results"));
            Set<String> received = new HashSet<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (received.size() < 3 && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (!record.key().equals(job.toString()) && !record.key().equals(task.toString())) continue;
                    assertThat(record.headers().toArray()).isEmpty();
                    byte[] bytes = record.value().getBytes(StandardCharsets.UTF_8);
                    switch (record.topic()) {
                        case "job-created" -> assertThat(new JacksonJsonDeserializer<>(JobCreatedEvent.class).deserialize(record.topic(), bytes).jobId()).isEqualTo(job);
                        case "task-queue" -> assertThat(new JacksonJsonDeserializer<>(TaskEvent.class).deserialize(record.topic(), bytes).generation()).isEqualTo(3);
                        case "task-results" -> assertThat(new JacksonJsonDeserializer<>(TaskResultEvent.class).deserialize(record.topic(), bytes).executionId()).isEqualTo(execution);
                        default -> throw new AssertionError("Unexpected topic");
                    }
                    received.add(record.topic());
                }
            }
            assertThat(received).hasSize(3);
        }
    }
    @Test void publishAckBeforeMarkCrashCanRedeliverTheSameLogicalIdentity() throws Exception {
        UUID id = insert(Instant.now());
        var first = store.claim(Duration.ofSeconds(30)).orElseThrow();
        kafka.send(first.topic(), first.key(), first.payload().getBytes(StandardCharsets.UTF_8)).get(10, TimeUnit.SECONDS);
        // Simulate death before marking. A new relay recovers exactly the persisted payload after lease expiry.
        jdbc.update("UPDATE outbox_events SET claimed_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?", id);
        var second = store.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(second.payload()).isEqualTo(first.payload());
        kafka.send(second.topic(), second.key(), second.payload().getBytes(StandardCharsets.UTF_8)).get(10, TimeUnit.SECONDS);
        assertThat(store.published(second)).isTrue();
        try (var consumer = consumer()) {
            consumer.subscribe(List.of("task-queue"));
            int delivered = 0;
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (delivered < 2 && System.nanoTime() < deadline)
                for (var record : consumer.poll(Duration.ofMillis(500)))
                    if (record.key().equals(first.key())) { assertThat(record.value()).isEqualTo(first.payload()); delivered++; }
            assertThat(delivered).isEqualTo(2);
        }
    }
    private KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of("bootstrap.servers", kafkaContainer.getBootstrapServers(),
                "group.id", UUID.randomUUID().toString(), "auto.offset.reset", "earliest"),
                new StringDeserializer(), new StringDeserializer());
    }
}
