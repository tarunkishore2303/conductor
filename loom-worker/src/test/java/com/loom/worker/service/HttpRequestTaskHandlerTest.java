package com.loom.worker.service;

import com.loom.worker.exception.TaskExecutionException;

import com.loom.capability.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HttpRequestTaskHandlerTest {
    private HttpServer server;
    private HttpRequestTaskHandler handler;
    private TaskCapabilityValidators validators;
    private String base;
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/ok", exchange -> { exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("ok".getBytes()); exchange.close(); });
        server.createContext("/post", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            int code = exchange.getRequestMethod().equals("POST") && new String(body, StandardCharsets.UTF_8).equals("{\"ready\":true}") ? 200 : 400;
            exchange.sendResponseHeaders(code, -1); exchange.close();
        });
        server.createContext("/fail", exchange -> { exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.createContext("/missing", exchange -> { exchange.sendResponseHeaders(404, -1); exchange.close(); });
        server.createContext("/large", exchange -> { exchange.sendResponseHeaders(200, 1024); exchange.getResponseBody().write(new byte[1024]); exchange.close(); });
        server.createContext("/chunked", exchange -> { exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write(new byte[1024]); exchange.close(); });
        server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().add("Location", "/ok"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        server.createContext("/large-header", exchange -> { exchange.getResponseHeaders().add("X-Large", "x".repeat(9000)); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.createContext("/many-headers", exchange -> {
            for (int i = 0; i < 70; i++) exchange.getResponseHeaders().add("X-Test-" + i, "x");
            exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.createContext("/slow", exchange -> { try { Thread.sleep(400); exchange.sendResponseHeaders(200, -1); } catch (Exception ignored) {} finally { exchange.close(); } });
        server.createContext("/drip", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                for (int i = 0; i < 100; i++) { exchange.getResponseBody().write(1); exchange.getResponseBody().flush(); Thread.sleep(10); }
            } catch (Exception ignored) {} finally { exchange.close(); }
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort();
        validators = mock(TaskCapabilityValidators.class);
        handler = new HttpRequestTaskHandler(properties(true), validators);
    }
    @AfterEach void cleanup() throws Exception { handler.close(); server.stop(0); }
    @Test void getSuccessDiscardsBody() throws Exception { assertThat(run("GET", "/ok", null, 1000).output()).isNull(); }
    @Test void postJson() throws Exception { run("POST", "/post", "{\"ready\":true}", 1000); }
    @Test void timeout() { assertFailure("/slow", 30, "HTTP_TIMEOUT"); }
    @Test void totalDeadlineStopsSlowDrip() { assertFailure("/drip", 80, "HTTP_TIMEOUT"); }
    @Test void serverError() { assertFailure("/fail", 1000, "HTTP_STATUS_500"); }
    @Test void clientError() { assertFailure("/missing", 1000, "HTTP_STATUS_404"); }
    @Test void advertisedOversizedBody() { assertFailure("/large", 1000, "HTTP_RESPONSE_TOO_LARGE"); }
    @Test void chunkedOversizedBody() { assertFailure("/chunked", 1000, "HTTP_RESPONSE_TOO_LARGE"); }
    @Test void redirectsAreNotFollowed() { assertFailure("/redirect", 1000, "HTTP_STATUS_302"); }
    @Test void oversizedHeaderRejected() { assertFailure("/large-header", 1000, "HTTP_CONNECTION_FAILURE"); }
    @Test void excessiveHeadersRejected() { assertFailure("/many-headers", 1000, "HTTP_CONNECTION_FAILURE"); }
    @Test void loopbackBlockedWithoutOptIn() throws Exception {
        handler.close(); handler = new HttpRequestTaskHandler(properties(false), validators);
        assertFailure("/ok", 1000, "HTTP_CONNECTION_FAILURE");
    }
    @Test void validationErrorIsSanitized() {
        doThrow(new IllegalArgumentException("authorization=secret")).when(validators).validate(any(), any());
        assertThatThrownBy(() -> run("GET", "/ok", null, 1000)).isInstanceOf(TaskExecutionException.class).hasMessageNotContaining("secret");
    }
    @ParameterizedTest @ValueSource(strings = {"file:///etc/passwd", "ftp://example.test/data", "not a url", "http://name:password@localhost/data", "http://[::ffff:169.254.169.254]/latest", "http://[fd00:ec2::254]/latest"})
    void invalidUrlsRejectedDefensively(String url) throws Exception {
        realValidators();
        assertThatThrownBy(() -> handler.execute(new TaskExecutionContext(null, new HttpRequestTaskConfig("GET", url, 1000, null, null))))
                .isInstanceOf(TaskExecutionException.class).hasMessage("HTTP task configuration is invalid");
    }
    @Test void oversizedRequestRejectedDefensively() throws Exception {
        realValidators();
        assertThatThrownBy(() -> run("POST", "/post", "x".repeat(16385), 1000)).isInstanceOf(TaskExecutionException.class);
    }
    private void realValidators() throws Exception {
        handler.close();
        var properties = properties(true);
        validators = new TaskCapabilityValidators(List.of(new NoOpTaskConfigValidator(), new DelayTaskConfigValidator(properties),
                new HttpRequestTaskConfigValidator(properties), new TransformTaskConfigValidator(properties)));
        handler = new HttpRequestTaskHandler(properties, validators);
    }
    private TaskExecutionResult run(String method, String path, String body, int timeout) throws Exception {
        return handler.execute(new TaskExecutionContext(null, new HttpRequestTaskConfig(method, base + path, timeout, body == null ? null : "application/json", body)));
    }
    private void assertFailure(String path, int timeout, String code) {
        assertThatThrownBy(() -> run("GET", path, null, timeout)).isInstanceOfSatisfying(TaskExecutionException.class, failure -> assertThat(failure.errorType()).isEqualTo(code));
    }
    private TaskProperties properties(boolean allowPrivate) {
        var defaults = TaskProperties.defaults();
        return new TaskProperties(defaults.delay(), new TaskProperties.Http(Duration.ofSeconds(2), 16384, 512, allowPrivate), defaults.transform());
    }
}
