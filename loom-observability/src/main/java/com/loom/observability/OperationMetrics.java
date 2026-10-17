package com.loom.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/** Explicit instrumentation: only application-owned operations and outcomes become tags. */
public final class OperationMetrics {
    private static final Logger log = LoggerFactory.getLogger(OperationMetrics.class);

    private OperationMetrics() {}

    public enum Operation {
        WORKFLOW_GENERATION("workflow_generation"), FAILURE_INTERPRETATION("failure_interpretation"),
        INCIDENT_SYNTHESIS("incident_synthesis"), EMBEDDING("embedding"),
        COPILOT_STEP("copilot_step"), EXECUTION_SUMMARY("execution_summary"),
        COPILOT("copilot", "ai.copilot.requests", "ai.copilot.failures", "ai.copilot.duration"),
        SUMMARY("summary", "ai.summary.requests", "ai.summary.failures", "ai.summary.duration"),
        INCIDENT_SEARCH("incident_search", "ai.incident.search.requests", "ai.incident.search.failures", "ai.incident.search.duration"),
        TASK_HANDLER("task_handler", "task.handler.requests", "task.handler.failures", "task.handler.duration");

        final String operation, requests, failures, duration;
        final boolean operationTag;
        Operation(String operation) {
            this(operation, "ai.requests", "ai.request.failures", "ai.request.duration", true);
        }
        Operation(String operation, String requests, String failures, String duration) {
            this(operation, requests, failures, duration, false);
        }
        Operation(String operation, String requests, String failures, String duration, boolean operationTag) {
            this.operation = operation; this.requests = requests; this.failures = failures;
            this.duration = duration; this.operationTag = operationTag;
        }
    }

    public enum Outcome { SUCCESS, FAILURE, CACHED, COALESCED }
    public enum HandlerType { NOOP, DELAY, HTTP_REQUEST, TRANSFORM }

    public static Scope startHandler(MeterRegistry registry, HandlerType handlerType) {
        return new Scope(registry, Operation.TASK_HANDLER, null, java.util.Objects.requireNonNull(handlerType));
    }

    public static Scope start(MeterRegistry registry, Operation operation) {
        return new Scope(registry, operation, null);
    }

    /** Model identity is bounded log metadata, never a metric tag. */
    public static Scope start(MeterRegistry registry, Operation operation, String model) {
        String safeModel = model == null ? null : model.replaceAll("[\\p{Cntrl}]", "_");
        if (safeModel != null && safeModel.length() > 128) safeModel = safeModel.substring(0, 128);
        return new Scope(registry, operation, safeModel);
    }

    public static final class Scope implements AutoCloseable {
        private final MeterRegistry registry;
        private final Operation operation;
        private final Timer.Sample timer;
        private final String correlationId = UUID.randomUUID().toString();
        private final String model;
        private final HandlerType handlerType;
        private Outcome outcome = Outcome.SUCCESS;
        private boolean closed;

        private Scope(MeterRegistry registry, Operation operation, String model) {
            this(registry, operation, model, null);
        }

        private Scope(MeterRegistry registry, Operation operation, String model, HandlerType handlerType) {
            this.registry = registry; this.operation = operation;
            this.model = model;
            this.handlerType = handlerType;
            timer = Timer.start(registry);
            registry.counter(operation.requests, tags()).increment();
        }

        public void outcome(Outcome outcome) { this.outcome = java.util.Objects.requireNonNull(outcome); }
        public void failure() { outcome = Outcome.FAILURE; }

        private String[] tags() {
            if (handlerType != null) return new String[] {"handler_type", handlerType.name()};
            return operation.operationTag ? new String[] {"operation", operation.operation} : new String[0];
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (outcome == Outcome.FAILURE) registry.counter(operation.failures, tags()).increment();
            String outcomeTag = outcome.name().toLowerCase(java.util.Locale.ROOT);
            Timer duration = handlerType != null
                    ? registry.timer(operation.duration, "handler_type", handlerType.name(), "outcome", outcomeTag)
                    : operation.operationTag
                    ? registry.timer(operation.duration, "operation", operation.operation, "outcome", outcomeTag)
                    : registry.timer(operation.duration, "outcome", outcomeTag);
            long elapsed = timer.stop(duration);
            log.info("operation={} correlationId={} model={} handlerType={} outcome={} durationMs={}",
                    operation.operation, correlationId, model, handlerType, outcomeTag, elapsed / 1_000_000);
        }
    }
}
