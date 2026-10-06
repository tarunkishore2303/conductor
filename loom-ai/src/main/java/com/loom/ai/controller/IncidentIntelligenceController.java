package com.loom.ai.controller;

import com.loom.ai.config.AiEmbeddingProperties;
import com.loom.ai.config.AiProperties;
import com.loom.ai.model.IncidentSynthesis;
import com.loom.ai.model.IncidentSynthesisRequest;
import com.loom.ai.service.IncidentEmbeddingService;
import com.loom.ai.service.IncidentSynthesisService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/incidents")
public class IncidentIntelligenceController {
    private final IncidentEmbeddingService embeddings;
    private final IncidentSynthesisService synthesis;
    private final AiProperties properties;
    private final AiEmbeddingProperties embeddingProperties;

    public IncidentIntelligenceController(IncidentEmbeddingService embeddings, IncidentSynthesisService synthesis,
                                          AiProperties properties, AiEmbeddingProperties embeddingProperties) {
        this.embeddings = embeddings;
        this.synthesis = synthesis;
        this.properties = properties;
        this.embeddingProperties = embeddingProperties;
    }

    @PostMapping("/embed")
    public EmbeddingResponse embed(@Valid @RequestBody EmbeddingRequest request) {
        return new EmbeddingResponse(embeddings.embed(request.text()), embeddingProperties.model());
    }

    @PostMapping("/synthesize")
    public SynthesisResponse synthesize(@Valid @RequestBody IncidentSynthesisRequest request) {
        return new SynthesisResponse(synthesis.synthesize(request), properties.model());
    }

    public record EmbeddingRequest(@NotBlank @Size(max = 4000) String text) {}
    public record EmbeddingResponse(float[] embedding, String model) {}
    public record SynthesisResponse(IncidentSynthesis synthesis, String model) {}
}
