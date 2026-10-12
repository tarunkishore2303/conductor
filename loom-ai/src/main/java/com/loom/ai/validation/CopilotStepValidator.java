package com.loom.ai.validation;

import com.loom.ai.exception.AiOutputValidationException;
import com.loom.ai.model.CopilotRequest;
import com.loom.ai.model.CopilotStep;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Validates allowlisted tool proposals and evidence-grounded read-only answers. */
public final class CopilotStepValidator {
    private static final Logger log = LoggerFactory.getLogger(CopilotStepValidator.class);
    private static final Set<String> TOOLS = Set.of("getRun", "getTasks", "getFailedTasks", "getTaskAttempts",
            "getFailureAnalysis", "getSimilarIncidents", "getWorkflow");
    private static final Pattern UUID_TEXT = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern MUTATION_CLAIM = Pattern.compile("(?i)\\bI (?:have )?(?:scheduled|retried|cancelled|canceled|deleted|updated|executed|started|modified)\\b");
    public void validate(CopilotStep result, CopilotRequest request) {
        if (result == null || result.evidenceIds() == null || result.evidenceIds().size() > 4
                || result.evidenceIds().stream().anyMatch(Objects::isNull)
                || new HashSet<>(result.evidenceIds()).size() != result.evidenceIds().size()) {
            invalid("AI returned an invalid copilot step.");
        }
        var knownEvidence = request.evidence().stream().map(CopilotRequest.Evidence::evidenceId).toList();
        if (!knownEvidence.containsAll(result.evidenceIds())) invalid("AI cited unknown evidence.");
        if ("TOOL".equals(result.action())) {
            validateTool(result, request);
        } else if ("ANSWER".equals(result.action())) {
            validateAnswer(result, request);
        } else {
            invalid("AI returned an unsupported copilot action.");
        }
    }

    private void validateTool(CopilotStep result, CopilotRequest request) {
        if (result.tool() == null || !TOOLS.contains(result.tool()) || !request.allowedTools().contains(result.tool())
                || (result.answer() != null && !result.answer().isBlank())) {
            invalid("AI proposed an unavailable or invalid tool.");
        }
        var arguments = result.arguments();
        String taskId = arguments == null ? null : arguments.taskId();
        Integer topK = arguments == null ? null : arguments.topK();
        if ("getTaskAttempts".equals(result.tool())) {
            if (taskId == null || !UUID_TEXT.matcher(taskId).matches() || topK != null) invalid("Invalid task tool arguments.");
        } else if ("getSimilarIncidents".equals(result.tool())) {
            if (taskId != null || (topK != null && (topK < 1 || topK > 5))) invalid("Invalid incident search arguments.");
        } else if (taskId != null || topK != null) {
            invalid("Unexpected copilot tool arguments.");
        }
    }

    private void validateAnswer(CopilotStep result, CopilotRequest request) {
        if (result.answer() == null || result.answer().isBlank() || result.answer().length() > 3000
                || (result.tool() != null && !result.tool().isBlank())
                || (result.arguments() != null && (result.arguments().taskId() != null || result.arguments().topK() != null))
                || MUTATION_CLAIM.matcher(result.answer()).find()) {
            invalid("AI returned an invalid read-only answer.");
        }
        var successful = request.evidence().stream().filter(CopilotRequest.Evidence::success).toList();
        if (!successful.isEmpty() && successful.stream().noneMatch(evidence -> result.evidenceIds().contains(evidence.evidenceId()))) {
            invalid("AI answer requires a successful evidence citation.");
        }
        var backedIds = new HashSet<String>();
        for (var evidence : successful) {
            if (!result.evidenceIds().contains(evidence.evidenceId())) continue;
            evidence.references().forEach(reference -> backedIds.add(reference.id().toLowerCase()));
            for (String scopeId : new String[]{request.scope().jobId(), request.scope().workflowId(), request.scope().analysisId()}) {
                if (scopeId != null && evidence.factsJson().contains(scopeId)) backedIds.add(scopeId.toLowerCase());
            }
        }
        var identifiers = UUID_TEXT.matcher(result.answer());
        while (identifiers.find()) {
            if (!backedIds.contains(identifiers.group().toLowerCase())) invalid("AI answer includes an unsupported identifier.");
        }
    }

    private void invalid(String message) {
        log.warn("AI operation=copilot_step validationRejected reason={}", message);
        throw new AiOutputValidationException(message);
    }
}
