package com.tarunkishore.loom_api.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Transport configuration only; the API never receives model-provider credentials. */
@ConfigurationProperties("conductor.ai")
public record AiServiceProperties(
        @DefaultValue("http://localhost:8085") String baseUrl,
        @DefaultValue("130s") Duration timeout) {
    public AiServiceProperties {
        URI uri = URI.create(baseUrl);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null)
            throw new IllegalArgumentException("AI service base URL must be HTTP(S) without credentials, query or fragment");
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("AI service timeout must be positive and at most five minutes");
    }
}
