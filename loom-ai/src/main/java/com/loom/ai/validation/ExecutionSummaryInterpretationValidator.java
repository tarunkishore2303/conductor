package com.loom.ai.validation;

import com.loom.ai.exception.AiOutputValidationException;
import com.loom.ai.model.ExecutionSummaryInterpretation;
import com.loom.ai.model.ExecutionSummaryRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounds interpretation and rejects identifiers absent from trusted facts. */
public final class ExecutionSummaryInterpretationValidator {
    private static final Pattern UUID_TEXT = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    public void validate(ExecutionSummaryInterpretation result, ExecutionSummaryRequest request) {
        if (result == null || !validText(result.overview(), 2000) || !validList(result.notableEvents()) || !validList(result.operationalNotes())) {
            throw new AiOutputValidationException("AI returned an invalid execution summary interpretation.");
        }
        var references = new HashSet<>(request.taskIds());
        references.add(request.jobId());
        if (request.failureAnalysisId() != null) references.add(request.failureAnalysisId());
        var normalized = references.stream().map(String::toLowerCase).collect(java.util.stream.Collectors.toSet());
        validateIdentifiers(result.overview(), normalized);
        result.notableEvents().forEach(text -> validateIdentifiers(text, normalized));
        result.operationalNotes().forEach(text -> validateIdentifiers(text, normalized));
    }

    private void validateIdentifiers(String text, Set<String> references) {
        var identifiers = UUID_TEXT.matcher(text);
        while (identifiers.find()) {
            if (!references.contains(identifiers.group().toLowerCase())) {
                throw new AiOutputValidationException("AI summary includes an unsupported execution identifier.");
            }
        }
    }

    private boolean validList(List<String> list) {
        return list != null && list.size() <= 5 && list.stream().allMatch(text -> validText(text, 1000));
    }

    private boolean validText(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
}
