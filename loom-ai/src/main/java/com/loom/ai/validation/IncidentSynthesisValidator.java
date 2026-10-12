package com.loom.ai.validation;

import com.loom.ai.exception.AiOutputValidationException;
import com.loom.ai.model.IncidentSynthesis;
import java.util.HashSet;
import java.util.Set;

/** Only retrieved incident identifiers may be cited by the model. */
public final class IncidentSynthesisValidator {
    public void validate(IncidentSynthesis result, Set<String> ids) {
            if (result == null || result.explanation() == null || result.explanation().isBlank() || result.explanation().length() > 2000
                    || result.citedIncidentIds() == null || result.citedIncidentIds().isEmpty() || result.citedIncidentIds().size() > 5
                    || result.citedIncidentIds().stream().anyMatch(id -> id == null || !ids.contains(id))
                    || new HashSet<>(result.citedIncidentIds()).size() != result.citedIncidentIds().size()) {
                throw new AiOutputValidationException("AI synthesis contains invalid or unsupported citations.");
            }
    }
}
