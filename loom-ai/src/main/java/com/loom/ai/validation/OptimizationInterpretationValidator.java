package com.loom.ai.validation;

import com.loom.ai.exception.AiOutputValidationException;
import com.loom.ai.model.OptimizationInterpretation;
import java.util.*;
import java.util.regex.Pattern;

/** Interpretation may cite only application-owned candidates and supplied execution identifiers. */
public final class OptimizationInterpretationValidator {
    private static final Pattern UUID_TEXT = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern CANDIDATE_TEXT = Pattern.compile("\\bO[0-9]+\\b");

    public void validate(OptimizationInterpretation result, Set<String> candidates, Set<String> identifiers) {
        if (result == null || result.recommendations() == null || result.recommendations().isEmpty()
                || result.recommendations().size() > 20) reject();
        text(result.overview(), 2000, candidates, identifiers);
        var cited = new HashSet<String>();
        for (var recommendation : result.recommendations()) {
            if (recommendation == null || recommendation.candidateIds() == null
                    || recommendation.candidateIds().isEmpty() || recommendation.candidateIds().size() > 20) reject();
            for (String id : recommendation.candidateIds()) {
                if (id == null || !candidates.contains(id) || !cited.add(id)) reject();
            }
            var local = new HashSet<>(recommendation.candidateIds());
            text(recommendation.recommendation(), 1000, local, identifiers);
            text(recommendation.rationale(), 1000, local, identifiers);
        }
    }

    private void text(String value, int max, Set<String> candidates, Set<String> identifiers) {
        if (value == null || value.isBlank() || value.length() > max) reject();
        var ids = UUID_TEXT.matcher(value);
        while (ids.find()) if (!identifiers.contains(ids.group().toLowerCase(Locale.ROOT))) reject();
        var citations = CANDIDATE_TEXT.matcher(value);
        while (citations.find()) if (!candidates.contains(citations.group())) reject();
    }

    private static void reject() { throw new AiOutputValidationException("AI returned an invalid optimization interpretation."); }
}
