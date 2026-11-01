package com.tarunkishore.loom_api.outbox;

import com.loom.common.outbox.JdbcOutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class OutboxConfiguration {
    @Bean
    JdbcOutboxWriter outboxWriter(JdbcTemplate jdbc) { return new JdbcOutboxWriter(jdbc); }

    @Bean
    @ConditionalOnProperty(name = "conductor.outbox.enabled", havingValue = "true", matchIfMissing = true)
    OutboxRelay outboxRelay(OutboxStore store, KafkaTemplate<String, byte[]> kafka,
            OutboxProperties properties, MeterRegistry metrics) {
        return new OutboxRelay(store, kafka, properties, metrics);
    }
}
