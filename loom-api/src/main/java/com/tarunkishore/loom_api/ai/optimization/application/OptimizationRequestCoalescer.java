package com.tarunkishore.loom_api.ai.optimization.application;

import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationReport;
import com.tarunkishore.loom_api.exception.AiUnavailableException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Same-process coalescing only. Database uniqueness remains the cross-replica safeguard. */
@Component
public final class OptimizationRequestCoalescer {
    private final ConcurrentHashMap<String, CompletableFuture<OptimizationReport>> inFlight = new ConcurrentHashMap<>();

    public OptimizationReport execute(String key, Supplier<OptimizationReport> generate,
            Runnable onCoalesced) {
        var promise = new CompletableFuture<OptimizationReport>();
        var existing = inFlight.putIfAbsent(key, promise);
        if (existing != null) {
            onCoalesced.run();
            return await(existing);
        }
        try {
            var report = generate.get();
            promise.complete(report);
            return report;
        } catch (RuntimeException failure) {
            promise.completeExceptionally(failure);
            throw failure;
        } finally {
            inFlight.remove(key, promise);
        }
    }

    private static OptimizationReport await(CompletableFuture<OptimizationReport> existing) {
        try {
            return existing.get(6, TimeUnit.MINUTES);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException();
        } catch (TimeoutException timeout) {
            throw new AiUnavailableException();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AiUnavailableException();
        }
    }

}
