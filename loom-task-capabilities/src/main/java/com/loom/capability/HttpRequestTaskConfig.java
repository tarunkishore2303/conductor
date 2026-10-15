package com.loom.capability;
public record HttpRequestTaskConfig(String method, String url, Integer timeoutMillis,
        String contentType, String body) implements TaskConfig { }
