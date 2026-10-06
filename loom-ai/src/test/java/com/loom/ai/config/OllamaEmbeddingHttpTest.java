package com.loom.ai.config;

import com.loom.ai.service.AiProviderUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class OllamaEmbeddingHttpTest {
    private final AiEmbeddingProperties embeddingProperties = new AiEmbeddingProperties("nomic-embed-text:v1.5", 768);

    @Test
    void nativeEmbeddingUsesDedicatedModelPrefixAndDoesNotTruncateEvidence() throws Exception {
        var captured = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            float[] vector = new float[768];
            vector[0] = 1;
            byte[] bytes = new JsonMapper().writeValueAsBytes(Map.of("model", embeddingProperties.model(), "embeddings", List.of(vector)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var model = new ModelConfiguration().incidentEmbeddingModel(properties(server, Duration.ofSeconds(2)), embeddingProperties);
            assertThat(model.embed("Current incident")).hasSize(768);
            var request = new JsonMapper().readTree(captured.get());
            assertThat(request.get("model").stringValue()).isEqualTo("nomic-embed-text:v1.5");
            assertThat(request.get("input").get(0).stringValue()).isEqualTo("search_document: Current incident");
            assertThat(request.get("truncate").booleanValue()).isFalse();
            assertThat(request.get("keep_alive").stringValue()).isEqualTo("1m");
        } finally { server.stop(0); }
    }

    @Test
    void embeddingOutageDoesNotRetryOrLeakProviderDetails() throws Exception {
        var attempts = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            attempts.incrementAndGet();
            byte[] bytes = "secret provider failure".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var model = new ModelConfiguration().incidentEmbeddingModel(properties(server, Duration.ofSeconds(2)), embeddingProperties);
            assertThatThrownBy(() -> model.embed("Incident")).isInstanceOf(AiProviderUnavailableException.class)
                    .hasMessageNotContaining("secret");
            assertThat(attempts.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }

    @Test
    void partialEmbeddingResponseBodyTimesOutOnce() throws Exception {
        var attempts = new AtomicInteger();
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/api/embed", exchange -> {
                attempts.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                try { release.await(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
            try {
                var model = new ModelConfiguration().incidentEmbeddingModel(properties(server, Duration.ofMillis(200)), embeddingProperties);
                long started = System.nanoTime();
                assertThatThrownBy(() -> model.embed("Incident")).isInstanceOf(AiProviderUnavailableException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
                assertThat(attempts.get()).isEqualTo(1);
            } finally { release.countDown(); server.stop(0); }
        }
    }

    private AiProperties properties(HttpServer server, Duration timeout) {
        return new AiProperties(true, "http://127.0.0.1:" + server.getAddress().getPort(), "chat-model", 0, timeout, 4096);
    }
}
