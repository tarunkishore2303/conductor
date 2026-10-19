package com.loom.ai.validation;

import com.loom.ai.exception.AiOutputValidationException;
import com.loom.ai.model.OptimizationInterpretation;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Interpretation may cite only application-owned candidates and supplied execution identifiers. */
public final class OptimizationInterpretationValidator {
    private static final Pattern UUID_TEXT = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    // Secondary fail-closed lexical safeguard only; schema, citation grounding and bounded
    // evidence remain authoritative. These phrases cannot establish semantic correctness.
    // Existing engine behavior is an application fact, not a model recommendation.
    private static final Pattern CONTRADICTORY_ADVICE = Pattern.compile(
            "(?is)\\b(?:enabl\\w*|introduc\\w*|implement\\w*|add\\w*)\\b.{0,80}\\b(?:parallel|concurrent)\\w*"
            + "|\\bimplement\\w*\\b.{0,60}\\b(?:retr(?:y|ies)|backoff)\\b"
            + "|\\b(?:significantly|guaranteed)\\s+(?:improv\\w*|speed\\w*|reduc\\w*)"
            + "|\\b(?:speedup|faster)\\b.{0,30}\\d+(?:\\.\\d+)?\\s*%"
            + "|\\b\\d+(?:\\.\\d+)?\\s*%\\s+(?:faster|speedup)\\b");
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
        if (CONTRADICTORY_ADVICE.matcher(value).find()) reject();
        var ids = UUID_TEXT.matcher(value);
        while (ids.find()) if (!identifiers.contains(ids.group().toLowerCase(Locale.ROOT))) reject();
        var citations = CANDIDATE_TEXT.matcher(value);
        while (citations.find()) if (!candidates.contains(citations.group())) reject();
    }

    private static void reject() { throw new AiOutputValidationException("AI returned an invalid optimization interpretation."); }
}
