package com.tarunkishore.loom_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("conductor.ai")
public record FailureContextProperties(@DefaultValue("32768") int failureContextMaxChars) {
    public FailureContextProperties {
        if (failureContextMaxChars < 1 || failureContextMaxChars > 65536)
            throw new IllegalArgumentException("Failure context budget must be between 1 and 65536 characters");
    }
}
