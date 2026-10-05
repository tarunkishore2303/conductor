package com.tarunkishore.loom_api.ai;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

/** Shared bounded transport, with the deadline covering headers AND body reads. */
final class BoundedAiJsonClient {
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration timeout;

    BoundedAiJsonClient(ObjectMapper mapper, String baseUrl, String path, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("AI timeout must be positive");
        this.mapper = mapper;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + path);
        this.timeout = timeout;
    }

    <T> T post(Object payload, Class<T> type) {
        long started = System.nanoTime();
        try {
            var request =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(timeout)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            mapper.writeValueAsString(payload)))
                            .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() == 422)
                    throw new AiOutputException("AI returned invalid structured output");
                if (response.statusCode() != 200) throw new AiUnavailableException();
                var read = new CompletableFuture<byte[]>();
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        read.complete(body.readNBytes(262145));
                                    } catch (java.io.IOException e) {
                                        read.completeExceptionally(e);
                                    }
                                });
                long remaining = timeout.toNanos() - (System.nanoTime() - started);
                if (remaining <= 0) throw new TimeoutException();
                byte[] bytes = read.get(remaining, TimeUnit.NANOSECONDS);
                if (bytes.length > 262144)
                    throw new AiOutputException("AI output exceeds the allowed size");
                return mapper.readValue(bytes, type);
            }
        } catch (AiOutputException | AiUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException();
        } catch (java.io.IOException | ExecutionException | TimeoutException e) {
            throw new AiUnavailableException();
        } catch (tools.jackson.core.JacksonException e) {
            throw new AiOutputException("AI returned malformed structured output");
        }
    }
}
