package com.loom.ai.service;

import com.loom.ai.config.AiEmbeddingProperties;
import com.loom.ai.model.IncidentEmbeddingModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IncidentEmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(IncidentEmbeddingService.class);
    private final IncidentEmbeddingModel model;
    private final AiEmbeddingProperties properties;
    private final MeterRegistry metrics;

    public IncidentEmbeddingService(IncidentEmbeddingModel model, AiEmbeddingProperties properties, MeterRegistry metrics) {
        this.model = model;
        this.properties = properties;
        this.metrics = metrics;
    }

    public float[] embed(String text) {
        if (text == null || text.isBlank() || text.length() > 4000) {
            throw new AiInvalidRequestException("Embedding text must contain between 1 and 4000 characters.");
        }
        var sample = Timer.start(metrics);
        String outcome = "success";
        metrics.counter("ai.requests", "operation", "embedding").increment();
        try {
            float[] result = model.embed(EvidenceRedactor.redact(text));
            validateVector(result);
            return result.clone();
        } catch (RuntimeException exception) {
            outcome = "failure";
            metrics.counter("ai.request.failures", "operation", "embedding").increment();
            throw exception;
        } finally {
            long elapsed = sample.stop(metrics.timer("ai.request.duration", "operation", "embedding", "outcome", outcome));
            log.info("AI operation=embedding model={} outcome={} durationMs={}", properties.model(), outcome, elapsed / 1_000_000);
        }
    }

    private void validateVector(float[] vector) {
        if (vector == null || vector.length != properties.dimensions()) {
            throw new AiOutputValidationException("Embedding has an unexpected dimension.");
        }
        double squaredNorm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new AiOutputValidationException("Embedding contains non-finite values.");
            squaredNorm += (double) value * value;
        }
        if (squaredNorm == 0) throw new AiOutputValidationException("Embedding is a zero vector.");
    }
}
