package com.tarunkishore.loom_api.live;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("conductor.run-stream")
public record RunStreamProperties(
        @DefaultValue("100") int maxConnections,
        @DefaultValue("50") int maxRuns,
        @DefaultValue("100") int maxTasksPerRun,
        @DefaultValue("1000") long observationIntervalMillis,
        @DefaultValue("15000") long heartbeatMillis,
        @DefaultValue("300000") long timeoutMillis) {
    public RunStreamProperties {
        if (maxConnections < 1 || maxConnections > 500 || maxRuns < 1 || maxRuns > 100
                || maxTasksPerRun < 1 || maxTasksPerRun > 500
                || observationIntervalMillis < 250 || observationIntervalMillis > 10000
                || heartbeatMillis < 5000 || heartbeatMillis > 30000
                || timeoutMillis < 10000 || timeoutMillis > 900000) {
            throw new IllegalArgumentException("Run stream configuration exceeds supported bounds");
        }
    }
}
