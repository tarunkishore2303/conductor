package com.loom.ai.provider;

import com.loom.ai.model.IncidentEmbeddingModel;
import com.loom.ai.service.AiProviderUnavailableException;
import com.loom.ai.service.AiOutputValidationException;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import java.util.List;

public class OllamaIncidentEmbeddingModel implements IncidentEmbeddingModel {
    private final EmbeddingModel model;
    private final OllamaEmbeddingOptions options;

    public OllamaIncidentEmbeddingModel(EmbeddingModel model, OllamaEmbeddingOptions options) {
        this.model = model;
        this.options = options;
    }

    @Override
    public float[] embed(String text) {
        try {
            var response = model.call(new EmbeddingRequest(List.of("search_document: " + text), options));
            if (response == null || response.getResults() == null || response.getResults().size() != 1
                    || response.getResults().getFirst() == null) {
                throw new AiOutputValidationException("AI returned an invalid embedding response.");
            }
            return response.getResults().getFirst().getOutput();
        } catch (AiOutputValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AiProviderUnavailableException();
        }
    }
}
