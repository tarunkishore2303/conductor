package com.loom.capability;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("conductor.tasks")
public record TaskProperties(@DefaultValue Delay delay, @DefaultValue Http http, @DefaultValue Transform transform) {
    public TaskProperties {
        if (delay == null || http == null || transform == null) throw new IllegalArgumentException("Task configuration groups are required");
    }
    public static TaskProperties defaults() { return new TaskProperties(new Delay(Duration.ofSeconds(30)),
            new Http(Duration.ofSeconds(10), 16384, 65536, false), new Transform(16384)); }
    public record Delay(@DefaultValue("30s") Duration maxDuration) {
        public Delay { bounded(maxDuration, Duration.ofSeconds(30), "delay max duration"); }
    }
    public record Http(@DefaultValue("10s") Duration maxTimeout, @DefaultValue("16384") int maxRequestBytes,
            @DefaultValue("65536") int maxResponseBytes, @DefaultValue("false") boolean allowPrivateAddresses) {
        public Http {
            bounded(maxTimeout, Duration.ofSeconds(10), "HTTP max timeout");
            if (maxRequestBytes < 1 || maxRequestBytes > 65536 || maxResponseBytes < 1 || maxResponseBytes > 1048576)
                throw new IllegalArgumentException("HTTP size limits exceed supported bounds");
        }
    }
    public record Transform(@DefaultValue("16384") int maxInputBytes) {
        public Transform { if (maxInputBytes < 1 || maxInputBytes > 65536) throw new IllegalArgumentException("Transform size limit exceeds supported bounds"); }
    }
    private static void bounded(Duration duration, Duration max, String name) {
        if (duration == null || duration.isNegative() || duration.isZero() || duration.compareTo(max) > 0)
            throw new IllegalArgumentException("Invalid " + name);
    }
}
