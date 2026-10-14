package com.loom.ai.validation;

import com.loom.ai.exception.AiOutputValidationException;
import com.loom.ai.model.FailureInterpretation;

/** Validates model-authored interpretation without authoring execution facts. */
public final class FailureInterpretationValidator {
    public void validate(FailureInterpretation result) {
        if (result == null || result.confidence() == null || !validText(result.likelyCause(), 1000)
                || !validText(result.explanation(), 4000) || result.recommendedActions() == null
                || result.recommendedActions().isEmpty() || result.recommendedActions().size() > 5
                || result.recommendedActions().stream().anyMatch(action -> !validText(action, 1000))) {
            throw new AiOutputValidationException("AI produced an invalid failure interpretation.");
        }
    }

    private boolean validText(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }
}
