package com.tarunkishore.loom_api.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("conductor.outbox")
public record OutboxProperties(@DefaultValue("true") boolean enabled,
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("30s") Duration claimTimeout,
        @DefaultValue("10s") Duration publishTimeout) {
    public OutboxProperties {
        bound(pollInterval, Duration.ofMillis(100), Duration.ofSeconds(30));
        bound(publishTimeout, Duration.ofMillis(100), Duration.ofSeconds(30));
        bound(claimTimeout, Duration.ofSeconds(1), Duration.ofMinutes(5));
        if (claimTimeout.compareTo(publishTimeout.multipliedBy(2)) <= 0)
            throw new IllegalArgumentException("Outbox claim timeout must exceed twice the publish timeout");
        if (batchSize < 1 || batchSize > 200) throw new IllegalArgumentException("Outbox batch size outside 1..200");
    }

    private static void bound(Duration value, Duration min, Duration max) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0)
            throw new IllegalArgumentException("Outbox duration outside supported bounds");
    }
}
