package com.loom.capability;
import java.util.List;

public final class TaskCapabilityCatalog {
    private final List<Capability> capabilities;
    public TaskCapabilityCatalog(TaskProperties properties) {
        capabilities = List.of(
            new Capability(TaskType.NOOP, "No external work; useful for orchestration", "Empty config {}"),
            new Capability(TaskType.DELAY, "Bounded wait", "durationMillis: 0.." + properties.delay().maxDuration().toMillis()),
            new Capability(TaskType.HTTP_REQUEST, "HTTP GET or POST, no redirects; response discarded", "method GET|POST; url http/https without credentials, query or fragment; no custom headers or authentication; timeoutMillis 1.." + properties.http().maxTimeout().toMillis() + "; body <= " + properties.http().maxRequestBytes() + " UTF-8 bytes; POST contentType application/json; private addresses " + (properties.http().allowPrivateAddresses() ? "opt-in enabled" : "blocked")),
            new Capability(TaskType.TRANSFORM, "Deterministic top-level JSON transform; static input, no dataflow", "operation JSON_SELECT with fields or JSON_RENAME with renames; input JSON object <= " + properties.transform().maxInputBytes() + " UTF-8 bytes; at most 50 simple field names"));
    }
    public List<Capability> capabilities() { return capabilities; }
    public record Capability(TaskType type, String description, String constraints) { }
}
