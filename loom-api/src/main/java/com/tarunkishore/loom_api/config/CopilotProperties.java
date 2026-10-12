package com.tarunkishore.loom_api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("conductor.ai")
public record CopilotProperties(@DefaultValue("180s") Duration copilotTimeout) {
    public CopilotProperties {
        if (copilotTimeout == null || copilotTimeout.isNegative() || copilotTimeout.isZero()
                || copilotTimeout.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("Copilot timeout must be positive and at most five minutes");
    }
}
