package com.loom.capability;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Shared hard ceiling; deployment configuration may impose a smaller bound. */
public final class TaskOutputLimits {
    public static final int MAX_BYTES = 65536;
    public static final String JSON = "application/json";
    public static final String TEXT = "text/plain";
    private static final Set<String> CONTENT_TYPES = Set.of(JSON, TEXT);

    private TaskOutputLimits() { }

    public static int utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    public static void validate(String output, String contentType, int maxBytes) {
        if (maxBytes < 1 || maxBytes > MAX_BYTES) throw new IllegalArgumentException("Invalid task output limit");
        if (output == null) {
            if (contentType != null) throw new IllegalArgumentException("Absent task output must not declare content type");
            return;
        }
        if (contentType == null || !CONTENT_TYPES.contains(contentType))
            throw new IllegalArgumentException("Unsupported task output content type");
        // PostgreSQL TEXT cannot represent NUL; malformed UTF-16 must not be silently replaced.
        if (output.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(output))
            throw new IllegalArgumentException("Task output must be valid database-safe UTF-8 text");
        if (utf8Bytes(output) > maxBytes) throw new IllegalArgumentException("Task output exceeds configured limit");
    }
}
