package com.tarunkishore.loom_api.ai.optimization.support;

import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Canonicalizes identified sets explicitly; attempts are ordered by sequence, not serialized JSON. */
@Component
public final class OptimizationContextFingerprint {
    private final JsonMapper mapper = JsonMapper.builder().build();

    public String fingerprint(OptimizationContext context) {
        var tasks = context.tasks().stream().sorted(Comparator.comparing(OptimizationContext.TaskStructure::taskId))
                .map(task -> new OptimizationContext.TaskStructure(task.taskId(), task.type(),
                        task.dependsOn().stream().sorted().toList())).toList();
        var attempts = Comparator.comparing(OptimizationContext.Attempt::attemptNumber, Comparator.nullsFirst(Integer::compareTo))
                .thenComparing(OptimizationContext.Attempt::startedAt, Comparator.nullsFirst(Instant::compareTo))
                .thenComparing(OptimizationContext.Attempt::completedAt, Comparator.nullsFirst(Instant::compareTo))
                .thenComparing(OptimizationContext.Attempt::status, Comparator.nullsFirst(String::compareTo))
                .thenComparing(OptimizationContext.Attempt::errorType, Comparator.nullsFirst(String::compareTo))
                .thenComparing(OptimizationContext.Attempt::retryScheduledAt, Comparator.nullsFirst(Instant::compareTo));
        var runs = context.runs().stream().sorted(Comparator.comparing(OptimizationContext.Run::jobId))
                .map(run -> new OptimizationContext.Run(run.jobId(), run.status(), run.createdAt(),
                        run.tasks().stream().sorted(Comparator.comparing(OptimizationContext.TaskRun::logicalId)
                                .thenComparing(OptimizationContext.TaskRun::taskId))
                                .map(task -> new OptimizationContext.TaskRun(task.taskId(), task.logicalId(), task.status(),
                                        task.deadLetteredAt(), task.attempts().stream().sorted(attempts).toList())).toList())).toList();
        var canonical = new OptimizationContext(context.workflowId(), context.workflowVersion(), tasks, runs,
                context.historyTruncated(), context.excludedRuns(), context.minSampleSize(), context.maxCandidates(),
                context.historyWindowSeconds(), context.limitations().stream().sorted().toList());
        return "v2:" + digest("optimization-evidence-v2:" + mapper.writeValueAsString(canonical));
    }

    public String reportHash(String factsJson, int policyVersion) {
        return digest("optimization-v" + policyVersion + ":" + factsJson);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
