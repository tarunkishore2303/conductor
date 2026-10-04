package com.loom.ai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("conductor.ai")
public record AiProperties(boolean enabled, String baseUrl, String model, double temperature,
                           Duration timeout, int maxTokens) {
    public AiProperties {
        if (baseUrl == null || !baseUrl.matches("https?://.+")) throw new IllegalArgumentException("AI base URL must use HTTP(S).");
        if (model == null || model.isBlank() || model.length() > 128 || model.endsWith(":cloud")) throw new IllegalArgumentException("AI model must be a local Ollama model.");
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 1) throw new IllegalArgumentException("AI temperature must be between 0 and 1.");
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0) throw new IllegalArgumentException("AI timeout must be positive and at most five minutes.");
        if (maxTokens < 1 || maxTokens > 4096) throw new IllegalArgumentException("AI max tokens must be between 1 and 4096.");
    }
}
