package com.loom.ai.service;

import java.util.regex.Pattern;

/** Redacts common credentials from evidence text before any local model invocation. */
public final class EvidenceRedactor {
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)(bearer|basic)\\s+[A-Za-z0-9+/_.=:-]+");
    private static final Pattern SECRET = Pattern.compile("(?i)(?<![A-Za-z0-9_])([\"']?(?:api[_-]?key|password|passwd|secret|token|authorization)[\"']?\\s*[=:]\\s*)(?:\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[^\\s,;&}\\]]+)");
    private static final Pattern USER_INFO = Pattern.compile("(https?://)[^/@\\s]+:[^/@\\s]+@");

    private EvidenceRedactor() {}

    public static String redact(String value) {
        if (value == null) return null;
        String redacted = AUTHORIZATION.matcher(value).replaceAll("$1 [REDACTED]");
        redacted = SECRET.matcher(redacted).replaceAll("$1[REDACTED]");
        return USER_INFO.matcher(redacted).replaceAll("$1[REDACTED]@");
    }
}
