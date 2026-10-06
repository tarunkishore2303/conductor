package com.loom.common.security;

import java.util.regex.Pattern;

/**
 * Redacts common credential formats before error messages become durable evidence.
 * This is a conservative baseline, not a guarantee for arbitrary secret formats.
 */
public final class FailureEvidenceSanitizer {
    private static final int MAX_LENGTH = 2048;
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[^\\s,;]+");
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)([\\\"']?(?:password|passwd|secret|token|api[_-]?key|authorization)[\\\"']?\\s*[:=]\\s*)"
                    + "(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;&]+)");
    private static final Pattern URL_CREDENTIAL = Pattern.compile(
            "(?i)(https?://)[^\\s/@]+:[^\\s/@]+@");

    private FailureEvidenceSanitizer() {}

    public static String sanitize(String message) {
        if (message == null) return null;
        // Bound regex work before processing untrusted exception messages.
        String bounded = message.substring(0, Math.min(message.length(), 8192));
        String redacted = AUTHORIZATION.matcher(bounded).replaceAll("$1 [REDACTED]");
        redacted = CREDENTIAL.matcher(redacted).replaceAll("$1[REDACTED]");
        redacted = URL_CREDENTIAL.matcher(redacted).replaceAll("$1[REDACTED]@");
        redacted = redacted.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        return redacted.substring(0, Math.min(redacted.length(), MAX_LENGTH));
    }
}
