package com.loom.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("conductor.ai.embedding")
public record AiEmbeddingProperties(String model, int dimensions) {
    public AiEmbeddingProperties {
        if (model == null || model.isBlank() || model.length() > 128 || model.endsWith(":cloud")) {
            throw new IllegalArgumentException("Embedding model must be a local Ollama model.");
        }
        if (dimensions != 768) {
            throw new IllegalArgumentException("Incident embedding dimensions must match the migrated 768-dimensional schema.");
        }
    }
}
