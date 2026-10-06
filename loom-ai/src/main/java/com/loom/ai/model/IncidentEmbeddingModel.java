package com.loom.ai.model;

public interface IncidentEmbeddingModel {
    float[] embed(String text);
}
