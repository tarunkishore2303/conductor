package com.loom.capability;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

public final class HttpRequestTaskConfigValidator implements TaskConfigValidator {
    private final TaskProperties properties;
    public HttpRequestTaskConfigValidator(TaskProperties properties) { this.properties = properties; }
    public TaskType type() { return TaskType.HTTP_REQUEST; }
    public void validate(TaskConfig config) {
        if (!(config instanceof HttpRequestTaskConfig http)) throw new IllegalArgumentException("HTTP_REQUEST requires HTTP configuration");
        if (!Set.of("GET", "POST").contains(http.method() == null ? "" : http.method())) throw new IllegalArgumentException("HTTP method must be GET or POST");
        URI uri;
        try { uri = URI.create(http.url() == null ? "" : http.url()); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid HTTP URL"); }
        if (http.url() == null || http.url().length() > 2048 || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getRawQuery() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getPort() == 0 || uri.getPort() > 65535)
            throw new IllegalArgumentException("Invalid HTTP URL");
        validateLiteralHost(uri.getHost(), properties.http().allowPrivateAddresses());
        if (http.timeoutMillis() == null || http.timeoutMillis() < 1 || http.timeoutMillis() > properties.http().maxTimeout().toMillis())
            throw new IllegalArgumentException("HTTP timeout is outside allowed bounds");
        if (http.body() != null && http.body().getBytes(StandardCharsets.UTF_8).length > properties.http().maxRequestBytes()) throw new IllegalArgumentException("HTTP body exceeds allowed size");
        if ("GET".equals(http.method()) && http.body() != null && !http.body().isEmpty()) throw new IllegalArgumentException("GET must not contain a body");
        if (http.contentType() != null && !"application/json".equals(http.contentType())) throw new IllegalArgumentException("Only application/json content type is supported");
        if ("POST".equals(http.method()) && http.body() != null && !"application/json".equals(http.contentType())) throw new IllegalArgumentException("POST body requires application/json content type");
    }
    /** No DNS lookup here: the worker validates and pins every resolved address at connection time. */
    private static void validateLiteralHost(String rawHost, boolean allowPrivate) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.equals("metadata.google.internal") || host.endsWith(".metadata.google.internal") || host.contains("%"))
            throw new IllegalArgumentException("HTTP target is not allowed");
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            if (!allowPrivate) throw new IllegalArgumentException("Private HTTP targets are disabled");
            return;
        }
        boolean ipv6 = host.contains(":");
        boolean numeric = host.matches("[0-9.]+") || host.startsWith("0x") || host.matches("(?i).*(^|\\.)0x[0-9a-f]+.*");
        if (!ipv6 && !numeric) return;
        if (!ipv6 && !host.matches("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}"))
            throw new IllegalArgumentException("Non-canonical HTTP address is not allowed");
        try {
            InetAddress address = InetAddress.getByName(host); // syntactically literal only; never a hostname
            HttpAddressPolicy.requireAllowed(address, allowPrivate);
        } catch (UnknownHostException invalid) { throw new IllegalArgumentException("Invalid HTTP address"); }
    }
}
