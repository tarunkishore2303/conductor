package com.loom.ai.service;

import com.loom.ai.exception.AiOutputValidationException;

import com.loom.ai.exception.AiInvalidRequestException;

import com.loom.ai.config.AiEmbeddingProperties;
import com.loom.ai.model.IncidentEmbeddingModel;
import io.micrometer.core.instrument.MeterRegistry;
import com.loom.observability.OperationMetrics;
import org.springframework.stereotype.Service;

@Service
public class IncidentEmbeddingService {
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
        var observation = OperationMetrics.start(metrics, OperationMetrics.Operation.EMBEDDING, properties.model());
        try {
            float[] result = model.embed(EvidenceRedactor.redact(text));
            validateVector(result);
            return result.clone();
        } catch (RuntimeException exception) {
            observation.outcome(OperationMetrics.Outcome.FAILURE);
            throw exception;
        } finally {
            observation.close();
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
