package com.tarunkishore.loom_api.ai;

import com.loom.common.security.FailureEvidenceSanitizer;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class IncidentDocumentBuilder {
    public List<Document> build(FailureContext context) {
        if (context == null
                || context.tasks() == null
                || context.tasks().isEmpty()
                || context.tasks().size() > 50)
            throw new AiOutputException("Invalid stored incident facts");
        var result = new ArrayList<Document>();
        for (var task : context.tasks()) {
            if (task == null
                    || task.taskId() == null
                    || task.status() == null
                    || task.attempts() == null
                    || task.attempts().size() > 300)
                throw new AiOutputException("Invalid stored task evidence");
            if (!Set.of("FAILED", "DEAD_LETTERED").contains(task.status())) continue;
            var text =
                    new StringBuilder("Task: ")
                            .append(clean(task.name(), 255))
                            .append("\nStatus: ")
                            .append(task.status())
                            .append("\nMaximum retries: ")
                            .append(task.maxRetries())
                            .append("\nRecorded attempts: ")
                            .append(task.attempts().size())
                            .append("\nDLQ state: ")
                            .append(
                                    task.status().equals("DEAD_LETTERED")
                                            ? "DEAD_LETTERED"
                                            : "not dead-lettered");
            for (var attempt : task.attempts()) {
                if (attempt == null) throw new AiOutputException("Invalid stored attempt evidence");
                if (attempt.errorMessage() == null && attempt.errorType() == null) continue;
                text.append("\nAttempt ")
                        .append(
                                attempt.attemptNumber() == null
                                        ? "unknown"
                                        : attempt.attemptNumber())
                        .append(": ")
                        .append(attempt.status())
                        .append("; ")
                        .append(clean(attempt.errorType(), 255))
                        .append(": ")
                        .append(clean(attempt.errorMessage(), 1024))
                        .append("; retry scheduled: ")
                        .append(attempt.retryScheduledAt() != null);
                if (text.length() >= 4000) break;
            }
            String document = text.substring(0, Math.min(text.length(), 4000));
            result.add(new Document(task.taskId(), document));
        }
        if (result.isEmpty())
            throw new AiOutputException("No failed-task incidents in stored analysis");
        return List.copyOf(result);
    }

    private static String clean(String text, int max) {
        if (text == null) return "unavailable";
        String safe = FailureEvidenceSanitizer.sanitize(text);
        return safe.substring(0, Math.min(safe.length(), max));
    }

    public record Document(UUID taskId, String documentText) {}
}
