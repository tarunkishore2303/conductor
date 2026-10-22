package com.loom.capability;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;
public record HttpRequestTaskConfig(String method, String url,
        @JsonProperty(required = true) @JsonDeserialize(using = StrictTaskNumbers.MillisInteger.class) Integer timeoutMillis,
        String contentType, String body, Boolean captureResponseBody, String bodyTaskId) implements TaskConfig {
    public HttpRequestTaskConfig {
        captureResponseBody = Boolean.TRUE.equals(captureResponseBody);
    }
    public HttpRequestTaskConfig(String method, String url, Integer timeoutMillis, String contentType, String body) {
        this(method, url, timeoutMillis, contentType, body, false, null);
    }
}
