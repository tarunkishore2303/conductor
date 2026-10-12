package com.tarunkishore.loom_api.ai.validation;

import com.tarunkishore.loom_api.exception.AiOutputException;
import com.tarunkishore.loom_api.ai.summary.ExecutionSummaryClient;
import com.tarunkishore.loom_api.ai.summary.ExecutionSummaryFacts;
import java.util.*;
import java.util.regex.Pattern;

/** Validates untrusted model interpretation against application-owned evidence. */
public final class ExecutionSummaryInterpretationValidator {
    private ExecutionSummaryInterpretationValidator() {}
    private static final Pattern UUID_TEXT =
            Pattern.compile(
                    "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    public static void validate(
            ExecutionSummaryClient.Result result, ExecutionSummaryFacts facts) {
        if (result == null || result.interpretation() == null)
            throw new AiOutputException("Empty execution summary interpretation");
        field(result.model(), 128);
        field(result.interpretation().overview(), 2000);
        var allowed = new HashSet<UUID>();
        allowed.add(facts.jobId());
        if (facts.failureAnalysisId() != null) allowed.add(facts.failureAnalysisId());
        if (facts.slowestObservedTasks() == null)
            throw new AiOutputException("Invalid summary task evidence");
        facts.slowestObservedTasks().forEach(task -> allowed.add(task.taskId()));
        references(result.interpretation().overview(), allowed);
        for (var list :
                List.of(
                        result.interpretation().notableEvents() == null
                                ? List.<String>of()
                                : result.interpretation().notableEvents(),
                        result.interpretation().operationalNotes() == null
                                ? List.<String>of()
                                : result.interpretation().operationalNotes())) {
            if (list.size() > 5)
                throw new AiOutputException("Execution summary lists exceed the allowed size");
            for (var text : list) {
                field(text, 1000);
                references(text, allowed);
            }
        }
        if (result.interpretation().notableEvents() == null
                || result.interpretation().operationalNotes() == null)
            throw new AiOutputException("Execution summary lists must be present");
    }

    private static void field(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new AiOutputException("Invalid structured execution summary");
    }

    private static void references(String text, Set<UUID> allowed) {
        var matcher = UUID_TEXT.matcher(text);
        while (matcher.find())
            if (!allowed.contains(UUID.fromString(matcher.group())))
                throw new AiOutputException("Execution summary references unsupported evidence");
    }

}
