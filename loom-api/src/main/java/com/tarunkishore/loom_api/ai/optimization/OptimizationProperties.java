package com.tarunkishore.loom_api.ai.optimization;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("conductor.optimization")
public record OptimizationProperties(@DefaultValue("50") int maxRuns,
        @DefaultValue("5") int minSampleSize, @DefaultValue("20") int maxCandidates,
        @DefaultValue("30d") Duration historyWindow, @DefaultValue("1000") int maxAttempts) {
    public OptimizationProperties {
        if (maxRuns < 1 || maxRuns > 50 || minSampleSize < 2 || minSampleSize > maxRuns
                || maxCandidates < 1 || maxCandidates > 20 || maxAttempts < 1 || maxAttempts > 1000
                || historyWindow == null || historyWindow.isNegative() || historyWindow.isZero()
                || historyWindow.compareTo(Duration.ofDays(365)) > 0)
            throw new IllegalArgumentException("Invalid bounded optimization configuration");
    }
    public static OptimizationProperties defaults() {
        return new OptimizationProperties(50, 5, 20, Duration.ofDays(30), 1000);
    }
}
