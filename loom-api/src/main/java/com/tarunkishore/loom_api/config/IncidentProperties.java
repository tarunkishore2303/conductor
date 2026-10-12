package com.tarunkishore.loom_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("conductor.ai")
public record IncidentProperties(
        @DefaultValue("nomic-embed-text:v1.5") String embeddingModel,
        @DefaultValue("qwen2.5-coder:7b") String chatModel,
        @DefaultValue("0.93") double incidentSimilarityThreshold,
        @DefaultValue("false") boolean incidentAutoIndexEnabled) {
    public IncidentProperties {
        if (embeddingModel == null || embeddingModel.isBlank() || embeddingModel.length() > 128)
            throw new IllegalArgumentException("Invalid embedding model");
        if (chatModel == null || chatModel.isBlank() || chatModel.length() > 128)
            throw new IllegalArgumentException("Invalid chat model");
        if (!Double.isFinite(incidentSimilarityThreshold)
                || incidentSimilarityThreshold < 0 || incidentSimilarityThreshold > 1)
            throw new IllegalArgumentException("Invalid similarity threshold");
    }
}
