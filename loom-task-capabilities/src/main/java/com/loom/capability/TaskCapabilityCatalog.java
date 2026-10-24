package com.loom.capability;
import java.util.List;

public final class TaskCapabilityCatalog {
    private final List<Capability> capabilities;
    public TaskCapabilityCatalog(TaskProperties properties) {
        capabilities = List.of(
            new Capability(TaskType.NOOP, "No external work; useful for orchestration", "Empty config {}"),
            new Capability(TaskType.DELAY, "Bounded wait", "durationMillis: 0.." + properties.delay().maxDuration().toMillis()),
            new Capability(TaskType.HTTP_REQUEST, "HTTP GET or POST, no redirects; response capture opt-in", "method GET|POST; url http/https without credentials, query or fragment; no custom headers or authentication; timeoutMillis 1.." + properties.http().maxTimeout().toMillis() + "; static body or bodyTaskId from one declared direct dependency, never both; body <= " + properties.http().maxRequestBytes() + " UTF-8 bytes; POST contentType application/json; captureResponseBody defaults false, captures only application/json or text/plain <= " + properties.output().maxBytes() + " UTF-8 bytes, persisted and must not contain secrets; private addresses " + (properties.http().allowPrivateAddresses() ? "opt-in enabled" : "blocked")),
            new Capability(TaskType.TRANSFORM, "Deterministic top-level JSON transform; whole-output direct-dependency input", "operation JSON_SELECT with fields or JSON_RENAME with renames; exactly one static input JSON object or inputTaskId from a declared direct dependency with application/json output; input <= " + properties.transform().maxInputBytes() + " UTF-8 bytes; output <= " + properties.output().maxBytes() + " UTF-8 bytes; at most 50 simple field names; no expressions or interpolation"));
    }
    public List<Capability> capabilities() { return capabilities; }
    public record Capability(TaskType type, String description, String constraints) { }
}
