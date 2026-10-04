package com.tarunkishore.loom_api.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

@Component
public class HttpAiWorkflowClient implements AiWorkflowClient {
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;
    private final ObjectMapper mapper;

    public HttpAiWorkflowClient(ObjectMapper mapper,
            @Value("${conductor.ai.base-url:http://localhost:8085}") String baseUrl,
            @Value("${conductor.ai.timeout:130s}") Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("AI timeout must be positive");
        this.mapper = mapper;
        this.timeout = timeout;
        this.endpoint = URI.create(baseUrl + "/internal/v1/workflows/generate");
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public Generation generate(String prompt) {
        long started = System.nanoTime();
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("prompt", prompt)))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() == 422) {
                    throw new AiOutputException("AI returned invalid structured workflow output");
                }
                if (response.statusCode() != 200) throw new AiUnavailableException();
                var read = new java.util.concurrent.CompletableFuture<byte[]>();
                Thread.ofVirtual().start(() -> {
                    try { read.complete(body.readNBytes(262145)); }
                    catch (java.io.IOException e) { read.completeExceptionally(e); }
                });
                long remaining = timeout.toNanos() - (System.nanoTime() - started);
                if (remaining <= 0) throw new java.util.concurrent.TimeoutException();
                byte[] bytes = read.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
                if (bytes.length > 262144) throw new AiOutputException("AI output exceeds the allowed size");
                return mapper.readValue(bytes, Generation.class);
            }
        } catch (AiOutputException | AiUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException();
        } catch (java.io.IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new AiUnavailableException();
        } catch (tools.jackson.core.JacksonException e) {
            throw new AiOutputException("AI returned malformed structured output");
        }
    }
}
