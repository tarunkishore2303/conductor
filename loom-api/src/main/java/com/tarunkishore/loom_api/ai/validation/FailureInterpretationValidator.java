package com.tarunkishore.loom_api.ai.validation;

import com.tarunkishore.loom_api.exception.AiOutputException;
import com.tarunkishore.loom_api.ai.FailureAnalysisClient;
import java.util.*;

/** Validates untrusted model interpretation against application-owned evidence. */
public final class FailureInterpretationValidator {
    private FailureInterpretationValidator() {}
    public static void validate(FailureAnalysisClient.Result result) {
        if (result == null || result.interpretation() == null)
            throw new AiOutputException("Empty failure interpretation");
        field(result.model(), 128);
        var i = result.interpretation();
        field(i.likelyCause(), 1000);
        field(i.explanation(), 4000);
        if (!Set.of("LOW", "MEDIUM", "HIGH").contains(i.confidence() == null ? "" : i.confidence()))
            throw new AiOutputException("Invalid interpretation confidence");
        if (i.recommendedActions() == null
                || i.recommendedActions().isEmpty()
                || i.recommendedActions().size() > 5)
            throw new AiOutputException("Interpretation requires 1 to 5 recommendations");
        i.recommendedActions().forEach(s -> field(s, 1000));
    }

    private static void field(String s, int max) {
        if (s == null || s.isBlank() || s.length() > max)
            throw new AiOutputException("Invalid structured failure interpretation");
    }

}
