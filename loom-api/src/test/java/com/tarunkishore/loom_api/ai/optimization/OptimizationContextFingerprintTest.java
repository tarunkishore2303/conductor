package com.tarunkishore.loom_api.ai.optimization;

import com.loom.capability.TaskType;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationClassification;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationContext;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationOutcome;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationType;
import com.tarunkishore.loom_api.ai.optimization.support.OptimizationContextFingerprint;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OptimizationContextFingerprintTest {
    private static final UUID WORKFLOW = UUID.fromString("10000000-0000-0000-0000-000000000000");
    private static final UUID RUN = UUID.fromString("20000000-0000-0000-0000-000000000000");
    private static final UUID TASK = UUID.fromString("30000000-0000-0000-0000-000000000000");
    private static final Instant START = Instant.parse("2026-10-19T12:00:00Z");
    private final OptimizationContextFingerprint fingerprints = new OptimizationContextFingerprint();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test void attemptSequenceUsesNumericOrderIncludingTwoAndTen() throws Exception {
        var canonical = context(List.of(definition("a", List.of())),
                List.of(run(RUN, List.of(task(List.of(attempt(0), attempt(2), attempt(10)))))));
        var shuffled = context(canonical.tasks(),
                List.of(run(RUN, List.of(task(List.of(attempt(10), attempt(0), attempt(2)))))));
        String expected = "v2:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("optimization-evidence-v2:" + mapper.writeValueAsString(canonical))
                        .getBytes(StandardCharsets.UTF_8)));

        assertThat(fingerprints.fingerprint(shuffled)).isEqualTo(expected);
        assertThat(fingerprints.fingerprint(canonical)).isEqualTo(expected);
    }

    @Test void identifiedCollectionsAndDependenciesIgnoreInputOrdering() {
        var first = context(List.of(definition("a", List.of()), definition("b", List.of("a", "z"))),
                List.of(run(RUN, List.of(task(List.of(attempt(0))))),
                        run(WORKFLOW, List.of(task(List.of(attempt(0)))))));
        var reordered = context(List.of(definition("b", List.of("z", "a")), definition("a", List.of())),
                List.of(first.runs().get(1), first.runs().get(0)));

        assertThat(fingerprints.fingerprint(reordered)).isEqualTo(fingerprints.fingerprint(first));
    }

    @Test void changedExecutionEvidenceChangesFingerprint() {
        var original = context(List.of(definition("a", List.of())),
                List.of(run(RUN, List.of(task(List.of(attempt(0)))))));
        var changed = context(original.tasks(), List.of(run(RUN, List.of(task(List.of(
                new OptimizationContext.Attempt(0, "FAILED", START, START.plusMillis(50),
                        "HTTP_STATUS_500", null)))))));

        assertThat(fingerprints.fingerprint(changed)).isNotEqualTo(fingerprints.fingerprint(original));
    }

    @Test void storedLegacyStructuralEnumsRemainReadable() {
        assertThat(mapper.readValue("\"PARALLELISM_CANDIDATE\"", OptimizationType.class))
                .isEqualTo(OptimizationType.PARALLEL_EXECUTION_OBSERVATION);
        assertThat(mapper.readValue("\"STRUCTURAL_CANDIDATE\"", OptimizationClassification.class))
                .isEqualTo(OptimizationClassification.STRUCTURAL_OBSERVATION);
        assertThat(mapper.writeValueAsString(OptimizationType.PARALLEL_EXECUTION_OBSERVATION))
                .isEqualTo("\"PARALLEL_EXECUTION_OBSERVATION\"");
    }

    @Test void typedOutcomePreservesExistingJsonValues() {
        for (var outcome : OptimizationOutcome.values()) {
            String json = "\"" + outcome.name() + "\"";
            assertThat(mapper.writeValueAsString(outcome)).isEqualTo(json);
            assertThat(mapper.readValue(json, OptimizationOutcome.class)).isEqualTo(outcome);
        }
    }

    private static OptimizationContext context(List<OptimizationContext.TaskStructure> definitions,
            List<OptimizationContext.Run> runs) {
        return new OptimizationContext(WORKFLOW, 1L, definitions, runs, false, 0, 5, 20,
                2592000, List.of("Bounded history"));
    }

    private static OptimizationContext.TaskStructure definition(String id, List<String> dependencies) {
        return new OptimizationContext.TaskStructure(id, TaskType.NOOP, dependencies);
    }

    private static OptimizationContext.Run run(UUID id, List<OptimizationContext.TaskRun> tasks) {
        return new OptimizationContext.Run(id, "COMPLETE", START, tasks);
    }

    private static OptimizationContext.TaskRun task(List<OptimizationContext.Attempt> attempts) {
        return new OptimizationContext.TaskRun(TASK, "a", "COMPLETE", null, attempts);
    }

    private static OptimizationContext.Attempt attempt(int number) {
        return new OptimizationContext.Attempt(number, "COMPLETE", START.plusSeconds(number),
                START.plusSeconds(number).plusMillis(50), null, null);
    }
}
