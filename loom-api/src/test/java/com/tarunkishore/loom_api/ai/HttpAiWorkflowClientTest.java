package com.tarunkishore.loom_api.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpAiWorkflowClientTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private HttpAiWorkflowClient client(int status, String body, Duration delay, Duration timeout) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/workflows/generate", exchange -> {
            try {
                if (!delay.isZero()) Thread.sleep(delay.toMillis());
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        return new HttpAiWorkflowClient(JsonMapper.builder().build(),
                "http://127.0.0.1:" + server.getAddress().getPort(), timeout);
    }

    @Test
    void stalledResponseBodyTimesOut() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/workflows/generate", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        var client = new HttpAiWorkflowClient(JsonMapper.builder().build(),
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(50));
        assertThatThrownBy(() -> client.generate("orders")).isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void providerUnavailableDoesNotExposeRemoteDetails() throws Exception {
        var client = client(500, "SECRET", Duration.ZERO, Duration.ofSeconds(1));
        assertThatThrownBy(() -> client.generate("orders"))
                .isInstanceOf(AiUnavailableException.class).hasMessageNotContaining("SECRET");
    }

    @Test
    void providerInvalidOutputMapsToDomainError() throws Exception {
        var client = client(422, "SECRET", Duration.ZERO, Duration.ofSeconds(1));
        assertThatThrownBy(() -> client.generate("orders"))
                .isInstanceOf(AiOutputException.class).hasMessageNotContaining("SECRET");
    }

    @Test
    void emptyOutputIsRejected() throws Exception {
        var client = client(200, "", Duration.ZERO, Duration.ofSeconds(1));
        assertThatThrownBy(() -> client.generate("orders")).isInstanceOf(AiOutputException.class);
    }

    @Test
    void malformedOutputIsRejected() throws Exception {
        var client = client(200, "invalid", Duration.ZERO, Duration.ofSeconds(1));
        assertThatThrownBy(() -> client.generate("orders")).isInstanceOf(AiOutputException.class);
    }

    @Test
    void timeoutMapsToUnavailable() throws Exception {
        var client = client(200, "{}", Duration.ofMillis(100), Duration.ofMillis(10));
        assertThatThrownBy(() -> client.generate("orders")).isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void oversizedOutputIsBounded() throws Exception {
        var client = client(200, "x".repeat(262145), Duration.ZERO, Duration.ofSeconds(1));
        assertThatThrownBy(() -> client.generate("orders"))
                .isInstanceOf(AiOutputException.class).hasMessageContaining("size");
    }

    @Test
    void successfulStructuredResponseMaps() throws Exception {
        var client = client(200,
                "{\"model\":\"fake\",\"workflow\":{\"name\":\"orders\",\"description\":null,\"tasks\":[]}}",
                Duration.ZERO, Duration.ofSeconds(1));
        assertThat(client.generate("orders").workflow().name()).isEqualTo("orders");
    }

    @Test
    void timeoutMustBePositive() {
        assertThatThrownBy(() -> new HttpAiWorkflowClient(JsonMapper.builder().build(),
                "http://localhost:8085", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
