package com.tarunkishore.loom_api.outbox;

import com.loom.observability.OperationMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Durable at-least-once publication: an ack/mark crash can cause redelivery, never lost intent. */
public class OutboxRelay {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);
    private final OutboxStore store;
    private final KafkaTemplate<String, byte[]> kafka;
    private final OutboxProperties properties;
    private final MeterRegistry metrics;
    private final AtomicLong pending = new AtomicLong();

    public OutboxRelay(OutboxStore store, KafkaTemplate<String, byte[]> kafka,
            OutboxProperties properties, MeterRegistry metrics) {
        this.store = store; this.kafka = kafka; this.properties = properties; this.metrics = metrics;
        Gauge.builder("conductor.outbox.pending", pending, AtomicLong::get).register(metrics);
    }

    @Scheduled(fixedDelayString = "${conductor.outbox.poll-interval:500ms}")
    public synchronized void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Outbox relay cannot publish inside a database transaction");
        try {
            pending.set(store.pendingCount());
            for (int i = 0; i < properties.batchSize(); i++) {
                var next = store.claim(properties.claimTimeout());
                if (next.isEmpty()) break;
                publish(next.get());
                if (Thread.currentThread().isInterrupted()) break;
            }
        } catch (Exception unavailable) {
            // Never log payloads, connection strings or raw broker/database errors.
            LOG.warn("Outbox polling unavailable; committed intent will be retried");
        }
    }

    private void publish(OutboxStore.Claim claim) {
        var observation = OperationMetrics.start(metrics, OperationMetrics.Operation.OUTBOX_PUBLISH);
        try {
            kafka.send(claim.topic(), claim.key(), claim.payload().getBytes(StandardCharsets.UTF_8))
                    .get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (store.published(claim)) metrics.counter("conductor.outbox.publish.success").increment();
        } catch (Exception failure) {
            observation.failure();
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            store.failed(claim);
            LOG.warn("Outbox publication pending outboxId={} eventType={} topic={} publishAttempt={}",
                    claim.id(), claim.type(), claim.topic(), claim.attempts());
        } finally {
            observation.close();
        }
    }
}
