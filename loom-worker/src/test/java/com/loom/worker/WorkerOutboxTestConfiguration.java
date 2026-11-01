package com.loom.worker;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Test transport only: the deployed relay belongs to loom-api. This fixture transports
 * committed due rows through real Kafka so worker tests exercise durable retry intent
 * independently of the worker that wrote it. API tests verify actual leases/recovery.
 */
@TestConfiguration
@EnableScheduling
class WorkerOutboxTestConfiguration {
    @Bean(destroyMethod = "destroy")
    DefaultKafkaProducerFactory<String, Object> testTaskProducerFactory() {
        Map<String, Object> props = producerProperties();
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        props.put(JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        var factory = new DefaultKafkaProducerFactory<String, Object>(props);
        factory.setPhysicalCloseTimeout(2);
        return factory;
    }

    @Bean
    KafkaTemplate<String, Object> testTaskProducer(DefaultKafkaProducerFactory<String, Object> testTaskProducerFactory) {
        return new KafkaTemplate<>(testTaskProducerFactory);
    }

    private static Map<String, Object> producerProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, TaskExecutionIT.kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return props;
    }

    @Bean(destroyMethod = "close")
    TestRelay testRelay(JdbcTemplate jdbc) { return new TestRelay(jdbc); }

    static class TestRelay implements AutoCloseable {
        private final JdbcTemplate jdbc;
        private final KafkaTemplate<String, byte[]> producer;
        private final DefaultKafkaProducerFactory<String, byte[]> producerFactory;
        private boolean initialized;
        private volatile boolean paused;
        private boolean closed;

        TestRelay(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
            Map<String, Object> props = producerProperties();
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
            producerFactory = new DefaultKafkaProducerFactory<>(props);
            producerFactory.setPhysicalCloseTimeout(2);
            producer = new KafkaTemplate<>(producerFactory);
        }

        synchronized void initialize() {
            if (initialized) return;
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('public.outbox_events') IS NOT NULL", Boolean.class))) {
                initialized = true;
                return;
            }
            try {
                String migration = new ClassPathResource("db/migration/V12__transactional_outbox.sql")
                        .getContentAsString(StandardCharsets.UTF_8);
                // Hibernate creates the entity columns in this worker-only fixture.
                jdbc.execute(migration.substring(migration.indexOf("CREATE TABLE outbox_events")));
                initialized = true;
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        }

        synchronized void pause() { paused = true; }
        synchronized void resume() { paused = false; }

        @Scheduled(fixedDelay = 100)
        synchronized void publishDue() {
            if (!initialized || paused) return;
            for (var row : jdbc.queryForList("SELECT id, topic, event_key, payload_json FROM outbox_events "
                    + "WHERE published_at IS NULL AND available_at <= CURRENT_TIMESTAMP ORDER BY created_at LIMIT 50")) {
                try {
                    producer.send((String) row.get("topic"), (String) row.get("event_key"),
                            ((String) row.get("payload_json")).getBytes(StandardCharsets.UTF_8)).get(10, TimeUnit.SECONDS);
                    jdbc.update("UPDATE outbox_events SET published_at=CURRENT_TIMESTAMP WHERE id=?", (UUID) row.get("id"));
                } catch (Exception error) { throw new IllegalStateException("Test transport failed", error); }
            }
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            paused = true;
            producer.destroy();
            producerFactory.destroy();
        }
    }
}
