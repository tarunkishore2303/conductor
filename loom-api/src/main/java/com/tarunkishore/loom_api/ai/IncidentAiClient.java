package com.tarunkishore.loom_api.ai;

import java.util.List;
import java.util.UUID;

public interface IncidentAiClient {
    Embedding embed(String text);

    SynthesisResult synthesize(SynthesisRequest request);

    record Embedding(float[] embedding, String model) {}

    record EvidenceMatch(
            UUID incidentId, UUID jobId, UUID taskId, String documentText, double similarity) {}

    record SynthesisRequest(
            UUID currentAnalysisId, String currentIncident, List<EvidenceMatch> matches) {}

    record Synthesis(String explanation, List<UUID> citedIncidentIds) {}

    record SynthesisResult(Synthesis synthesis, String model) {}
}
