package com.loom.ai.service;

import com.loom.ai.model.AttemptFailureFacts;
import com.loom.ai.model.FailureFactsRequest;
import com.loom.ai.model.TaskFailureFacts;
import org.springframework.stereotype.Component;
import java.util.regex.Pattern;

/** Defense in depth: the API also sanitizes persisted evidence before transport. */
@Component
public class FailureContextSanitizer {
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)(bearer|basic)\\s+[A-Za-z0-9+/_.=:-]+");
    private static final Pattern SECRET = Pattern.compile("(?i)((?:api[_-]?key|password|passwd|secret|token|authorization)\\s*[=:]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)");
    private static final Pattern USER_INFO = Pattern.compile("(https?://)[^/@\\s]+:[^/@\\s]+@");

    public FailureFactsRequest sanitize(FailureFactsRequest facts) {
        return new FailureFactsRequest(facts.jobId(), facts.jobStatus(), facts.tasks().stream().map(task ->
                new TaskFailureFacts(task.taskId(), redact(task.name()), task.status(), task.maxRetries(),
                        task.attempts().stream().map(attempt -> new AttemptFailureFacts(attempt.attemptNumber(),
                                attempt.status(), attempt.startedAt(), attempt.completedAt(), redact(attempt.errorType()),
                                redact(attempt.errorMessage()), attempt.retryScheduledAt())).toList(), task.deadLetteredAt())).toList(),
                facts.unavailableEvidence().stream().map(this::redact).toList());
    }

    private String redact(String value) {
        if (value == null) return null;
        String redacted = AUTHORIZATION.matcher(value).replaceAll("$1 [REDACTED]");
        redacted = SECRET.matcher(redacted).replaceAll("$1[REDACTED]");
        return USER_INFO.matcher(redacted).replaceAll("$1[REDACTED]@");
    }
}
