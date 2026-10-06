package com.loom.ai.service;

import com.loom.ai.model.AttemptFailureFacts;
import com.loom.ai.model.FailureFactsRequest;
import com.loom.ai.model.TaskFailureFacts;
import org.springframework.stereotype.Component;

/** Defense in depth: the API also sanitizes persisted evidence before transport. */
@Component
public class FailureContextSanitizer {

    public FailureFactsRequest sanitize(FailureFactsRequest facts) {
        return new FailureFactsRequest(facts.jobId(), facts.jobStatus(), facts.tasks().stream().map(task ->
                new TaskFailureFacts(task.taskId(), redact(task.name()), task.status(), task.maxRetries(),
                        task.attempts().stream().map(attempt -> new AttemptFailureFacts(attempt.attemptNumber(),
                                attempt.status(), attempt.startedAt(), attempt.completedAt(), redact(attempt.errorType()),
                                redact(attempt.errorMessage()), attempt.retryScheduledAt())).toList(), task.deadLetteredAt())).toList(),
                facts.unavailableEvidence().stream().map(this::redact).toList());
    }

    private String redact(String value) {
        return EvidenceRedactor.redact(value);
    }
}
