package com.loom.worker.service;

import com.loom.worker.exception.TaskExecutionException;

import com.loom.capability.*;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.SocketTimeoutException;
import java.util.concurrent.*;

@Component
public final class HttpRequestTaskHandler implements TaskHandler {
    private final TaskProperties properties;
    private final TaskCapabilityValidators validators;
    private final CloseableHttpClient client;
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();

    public HttpRequestTaskHandler(TaskProperties properties, TaskCapabilityValidators validators) {
        this.properties = properties;
        this.validators = validators;
        var timeout = Timeout.ofMilliseconds(properties.http().maxTimeout().toMillis());
        var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setConnectionFactory(ManagedHttpClientConnectionFactory.builder()
                        .http1Config(Http1Config.custom().setMaxHeaderCount(64).setMaxLineLength(8192).build()).build())
                .setDnsResolver(new GuardedDnsResolver(properties.http().allowPrivateAddresses()))
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout).build())
                .build();
        this.client = HttpClients.custom().setConnectionManager(connections).disableRedirectHandling()
                .disableAutomaticRetries().disableCookieManagement().disableContentCompression().build();
    }
    @Override public TaskType type() { return TaskType.HTTP_REQUEST; }
    @Override public TaskExecutionResult execute(TaskExecutionContext context) throws Exception {
        if (!(context.config() instanceof HttpRequestTaskConfig config))
            throw new TaskExecutionException("INVALID_HTTP_CONFIG", "HTTP task configuration is invalid");
        try { validators.validate(type(), config); }
        catch (IllegalArgumentException invalid) { throw new TaskExecutionException("INVALID_HTTP_CONFIG", "HTTP task configuration is invalid"); }
        long timeoutMillis = config.timeoutMillis() == null ? properties.http().maxTimeout().toMillis() : config.timeoutMillis();
        var request = new HttpUriRequestBase(config.method(), URI.create(config.url()));
        request.setConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofMilliseconds(timeoutMillis))
                .setResponseTimeout(Timeout.ofMilliseconds(timeoutMillis)).setRedirectsEnabled(false).build());
        if (config.body() != null) request.setEntity(new StringEntity(config.body(), ContentType.APPLICATION_JSON));
        Future<TaskExecutionResult> work = requests.submit(() -> client.execute(request, response -> {
            int code = response.getCode();
            if (code < 200 || code >= 300) {
                request.cancel();
                throw new HttpFailure("HTTP_STATUS_" + code, "HTTP endpoint returned status " + code);
            }
            var entity = response.getEntity();
            if (entity != null) {
                int max = properties.http().maxResponseBytes();
                if (entity.getContentLength() > max) { request.cancel(); throw new HttpFailure("HTTP_RESPONSE_TOO_LARGE", "HTTP response exceeds configured limit"); }
                try (var input = entity.getContent()) {
                    byte[] buffer = new byte[4096];
                    long total = 0;
                    for (int read; (read = input.read(buffer)) != -1;) {
                        total += read;
                        if (total > max) { request.cancel(); throw new HttpFailure("HTTP_RESPONSE_TOO_LARGE", "HTTP response exceeds configured limit"); }
                    }
                }
            }
            return TaskExecutionResult.completed();
        }));
        try { return work.get(timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (TimeoutException timeout) {
            request.cancel(); work.cancel(true);
            throw new TaskExecutionException("HTTP_TIMEOUT", "HTTP request exceeded its time limit");
        } catch (InterruptedException interrupted) {
            request.cancel(); work.cancel(true); Thread.currentThread().interrupt(); throw interrupted;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof HttpFailure safe) throw new TaskExecutionException(safe.code, safe.getMessage());
            if (cause instanceof SocketTimeoutException) throw new TaskExecutionException("HTTP_TIMEOUT", "HTTP request exceeded its time limit");
            throw new TaskExecutionException("HTTP_CONNECTION_FAILURE", "HTTP request could not complete");
        }
    }
    @PreDestroy public void close() throws Exception { requests.shutdownNow(); client.close(); }
    private static final class HttpFailure extends java.io.IOException {
        private final String code;
        HttpFailure(String code, String message) { super(message); this.code = code; }
    }
}
