package com.loom.capability;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;
public record HttpRequestTaskConfig(String method, String url,
        @JsonProperty(required = true) @JsonDeserialize(using = StrictTaskNumbers.MillisInteger.class) Integer timeoutMillis,
        String contentType, String body) implements TaskConfig { }
