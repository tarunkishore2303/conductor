package com.loom.common.event;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Stable delivery identity; historical generation-zero IDs must remain unchanged. */
public final class ExecutionIdentity {
    private ExecutionIdentity() {}

    public static UUID id(UUID taskId, int generation, int attempt) {
        if (generation < 0 || attempt < 0) throw new IllegalArgumentException("Negative execution identity");
        String value = generation == 0 ? taskId + "-" + attempt : taskId + ":" + generation + ":" + attempt;
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
