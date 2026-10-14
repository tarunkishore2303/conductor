package com.tarunkishore.loom_api.ai.validation;

import com.tarunkishore.loom_api.exception.AiOutputException;
import com.tarunkishore.loom_api.ai.copilot.CopilotClient;
import java.util.*;
import java.util.regex.Pattern;

/** Validates untrusted model interpretation against application-owned evidence. */
public final class CopilotAnswerValidator {
    private CopilotAnswerValidator() {}
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final int MAX_TOOLS = 4;
    public static void validate(CopilotClient.Step step, List<CopilotClient.Evidence> evidence) {
        if (step.answer() == null || step.answer().isBlank() || step.answer().length() > 3000
                || step.evidenceIds() == null || step.evidenceIds().size() > MAX_TOOLS
                || new HashSet<>(step.evidenceIds()).size() != step.evidenceIds().size())
            throw new AiOutputException("Invalid copilot answer or citations");
        var allowed = new HashSet<String>();
        var knownEvidence = new HashSet<String>();
        for (var item : evidence) {
            knownEvidence.add(item.evidenceId());
            if (item.success() && step.evidenceIds().contains(item.evidenceId()))
                item.references().forEach(ref -> allowed.add(ref.id().toString().toLowerCase(Locale.ROOT)));
        }
        if (!knownEvidence.containsAll(step.evidenceIds())
                || (evidence.stream().anyMatch(CopilotClient.Evidence::success)
                && evidence.stream().noneMatch(item -> item.success() && step.evidenceIds().contains(item.evidenceId()))))
            throw new AiOutputException("Copilot must cite retrieved evidence");
        var ids = UUID_PATTERN.matcher(step.answer());
        while (ids.find()) if (!allowed.contains(ids.group().toLowerCase(Locale.ROOT)))
            throw new AiOutputException("Copilot answer contains an ungrounded identifier");
    }

}
