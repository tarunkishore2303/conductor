package com.tarunkishore.loom_api.ai.validation;

import com.tarunkishore.loom_api.ai.optimization.client.WorkflowOptimizationClient;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationContext;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationFacts;
import com.tarunkishore.loom_api.exception.AiOutputException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Transport/model output is untrusted; only application-generated candidates may be cited. */
public final class OptimizationInterpretationValidator {
    private static final Pattern UUID_TEXT = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    // Secondary fail-closed lexical guard only; schema, grounded citations and bounded evidence
    // are primary. These phrases cannot prove prose correctness and may reject negations.
    private static final Pattern CONTRADICTORY_ADVICE = Pattern.compile(
            "(?is)\\b(?:enabl\\w*|introduc\\w*|implement\\w*|add\\w*)\\b.{0,80}\\b(?:parallel|concurrent)\\w*"
            + "|\\bimplement\\w*\\b.{0,60}\\b(?:retr(?:y|ies)|backoff)\\b"
            + "|\\b(?:significantly|guaranteed)\\s+(?:improv\\w*|speed\\w*|reduc\\w*)"
            + "|\\b(?:speedup|faster)\\b.{0,30}\\d+(?:\\.\\d+)?\\s*%"
            + "|\\b\\d+(?:\\.\\d+)?\\s*%\\s+(?:faster|speedup)\\b");
    private static final Pattern CANDIDATE_TEXT = Pattern.compile("\\bO[0-9]+\\b");
    private OptimizationInterpretationValidator() {}
    public static void validate(WorkflowOptimizationClient.Result result, OptimizationFacts facts) {
        if (result == null || result.interpretation() == null) invalid();
        field(result.model(), 128); field(result.interpretation().overview(), 2000);
        Set<String> candidates = new HashSet<>();
        Set<String> uuids = new HashSet<>(); uuids.add(facts.workflowId().toString().toLowerCase(Locale.ROOT));
        facts.runIds().forEach(id -> uuids.add(id.toString().toLowerCase(Locale.ROOT)));
        if (facts.taskDefinitions() != null) facts.taskDefinitions().stream()
                .map(OptimizationContext.TaskStructure::taskId).filter(id -> UUID_TEXT.matcher(id).matches())
                .forEach(id -> uuids.add(id.toLowerCase(Locale.ROOT)));
        for (var candidate : facts.candidates()) {
            candidates.add(candidate.candidateId());
            candidate.taskReferences().stream().filter(x -> UUID_TEXT.matcher(x).matches())
                    .forEach(x -> uuids.add(x.toLowerCase(Locale.ROOT)));
        }
        validateText(result.interpretation().overview(), candidates, uuids);
        var recommendations = result.interpretation().recommendations();
        if (recommendations == null || recommendations.size() > 20
                || (!candidates.isEmpty() && recommendations.isEmpty())
                || (candidates.isEmpty() && !recommendations.isEmpty())) invalid();
        Set<String> cited = new HashSet<>();
        for (var recommendation : recommendations) {
            if (recommendation == null || recommendation.candidateIds() == null
                    || recommendation.candidateIds().isEmpty() || recommendation.candidateIds().size() > 20) invalid();
            for (String id : recommendation.candidateIds())
                if (!candidates.contains(id) || !cited.add(id)) invalid();
            field(recommendation.recommendation(), 1000); field(recommendation.rationale(), 1000);
            validateText(recommendation.recommendation(), new HashSet<>(recommendation.candidateIds()), uuids);
            validateText(recommendation.rationale(), new HashSet<>(recommendation.candidateIds()), uuids);
        }
    }
    private static void validateText(String text, Set<String> candidates, Set<String> uuids) {
        if (CONTRADICTORY_ADVICE.matcher(text).find()) invalid();
        var ids = UUID_TEXT.matcher(text);
        while (ids.find()) if (!uuids.contains(ids.group().toLowerCase(Locale.ROOT))) invalid();
        var refs = CANDIDATE_TEXT.matcher(text);
        while (refs.find()) if (!candidates.contains(refs.group())) invalid();
    }
    private static void field(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) invalid();
    }
    private static void invalid() { throw new AiOutputException("Invalid or ungrounded optimization interpretation"); }
}
