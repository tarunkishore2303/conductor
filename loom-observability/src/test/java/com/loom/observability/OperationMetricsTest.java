package com.loom.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OperationMetricsTest {
    @Test void recordsSuccessfulOperationOnce() {
        var registry = new SimpleMeterRegistry();
        var scope = OperationMetrics.start(registry, OperationMetrics.Operation.WORKFLOW_GENERATION);
        scope.close(); scope.close();
        assertThat(registry.get("ai.requests").tag("operation", "workflow_generation").counter().count()).isEqualTo(1);
        assertThat(registry.get("ai.request.duration").tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.find("ai.request.failures").counter()).isNull();
    }

    @Test void failurePreservesExceptionAndRecordsSafeTags() {
        var registry = new SimpleMeterRegistry();
        var expected = new IllegalStateException("sensitive error");
        assertThatThrownBy(() -> {
            try (var scope = OperationMetrics.start(registry, OperationMetrics.Operation.COPILOT_STEP)) {
                try { throw expected; } catch (RuntimeException failure) { scope.failure(); throw failure; }
            }
        }).isSameAs(expected);
        assertThat(registry.get("ai.request.failures").counter().count()).isEqualTo(1);
        assertThat(registry.get("ai.request.duration").tag("outcome", "failure").timer().count()).isEqualTo(1);
        registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag -> {
            assertThat(tag.getKey()).isIn("operation", "outcome");
            assertThat(tag.getValue()).isIn("copilot_step", "failure");
        }));
    }

    @Test void cachedSummaryKeepsExistingMetricFamily() {
        var registry = new SimpleMeterRegistry();
        try (var scope = OperationMetrics.start(registry, OperationMetrics.Operation.SUMMARY)) {
            scope.outcome(OperationMetrics.Outcome.CACHED);
        }
        assertThat(registry.get("ai.summary.requests").counter().count()).isEqualTo(1);
        assertThat(registry.get("ai.summary.duration").tag("outcome", "cached").timer().count()).isEqualTo(1);
    }
}
