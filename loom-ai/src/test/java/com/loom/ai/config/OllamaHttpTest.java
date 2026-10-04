package com.loom.ai.config;

import com.loom.ai.service.AiOutputValidationException;
import com.loom.ai.service.AiProviderUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class OllamaHttpTest {
    @Test
    void nativeSchemaIsSentOverHttpAndValidOutputIsMapped() throws Exception {
        var captured = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String content = """
                    {"name":"Demo","description":"Orders","tasks":[{"identifier":"download","name":"Download","type":"NOOP","dependencies":[],"maxRetries":1}]}
                    """;
            byte[] bytes = new JsonMapper().writeValueAsBytes(Map.of("model", "test-model", "done", true,
                    "message", Map.of("role", "assistant", "content", content)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var model = new ModelConfiguration().workflowGenerationModel(properties(server, Duration.ofSeconds(2)));
            assertThat(model.generate("Download orders").tasks()).hasSize(1);
            var request = new JsonMapper().readTree(captured.get());
            assertThat(request.get("format").isObject()).isTrue();
            assertThat(request.get("format").toString()).contains("required", "identifier");
            assertThat(request.get("model").stringValue()).isEqualTo("test-model");
            assertThat(request.get("options").get("num_ctx").intValue()).isEqualTo(8192);
            assertThat(request.get("options").get("num_predict").intValue()).isEqualTo(4096);
        } finally { server.stop(0); }
    }

    @Test
    void timeoutIsBoundedAndDoesNotRetry() throws Exception {
        var requests = new AtomicInteger();
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/api/chat", exchange -> {
                requests.incrementAndGet();
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
                var model = new ModelConfiguration().workflowGenerationModel(properties(server, Duration.ofMillis(200)));
                long started = System.nanoTime();
                assertThatThrownBy(() -> model.generate("Download orders")).isInstanceOf(AiProviderUnavailableException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
                assertThat(requests.get()).isEqualTo(1);
            } finally { release.countDown(); server.stop(0); }
        }
    }

    @Test
    void unreachableOllamaMapsToUnavailable() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var properties = properties(server, Duration.ofMillis(200));
        server.stop(0);
        var model = new ModelConfiguration().workflowGenerationModel(properties);
        assertThatThrownBy(() -> model.generate("Download orders")).isInstanceOf(AiProviderUnavailableException.class);
    }

    private AiProperties properties(HttpServer server, Duration timeout) {
        return new AiProperties(true, "http://127.0.0.1:" + server.getAddress().getPort(), "test-model", 0, timeout, 4096);
    }
}
