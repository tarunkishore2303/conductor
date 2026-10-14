package com.tarunkishore.loom_api.ai.validation;

import com.tarunkishore.loom_api.exception.AiOutputException;
import com.tarunkishore.loom_api.ai.IncidentAiClient;
import java.util.*;

/** Validates untrusted model interpretation against application-owned evidence. */
public final class IncidentSynthesisValidator {
    private IncidentSynthesisValidator() {}
    public static void validate(
            IncidentAiClient.SynthesisResult response,
            List<IncidentAiClient.EvidenceMatch> evidence) {
        if (response == null
                || response.synthesis() == null
                || response.model() == null
                || response.model().isBlank()
                || response.model().length() > 128)
            throw new AiOutputException("Invalid incident synthesis");
        var synthesis = response.synthesis();
        if (synthesis.explanation() == null
                || synthesis.explanation().isBlank()
                || synthesis.explanation().length() > 2000
                || synthesis.citedIncidentIds() == null
                || synthesis.citedIncidentIds().isEmpty()
                || synthesis.citedIncidentIds().size() > 5)
            throw new AiOutputException("Incident synthesis must cite retrieved evidence");
        var allowed = new HashSet<UUID>();
        evidence.forEach(e -> allowed.add(e.incidentId()));
        if (new HashSet<>(synthesis.citedIncidentIds()).size()
                != synthesis.citedIncidentIds().size())
            throw new AiOutputException("Incident synthesis contains duplicate citations");
        if (!allowed.containsAll(synthesis.citedIncidentIds()))
            throw new AiOutputException("Incident synthesis cites unretrieved evidence");
    }

}
