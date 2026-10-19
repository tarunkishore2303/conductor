package com.tarunkishore.loom_api.ai.optimization;

import java.nio.charset.StandardCharsets;

import com.tarunkishore.loom_api.ai.optimization.support.OptimizationContextFingerprint;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationType;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationSeverity;

import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationOutcome;

import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationContext;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationCandidate;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationClassification;
import com.tarunkishore.loom_api.ai.optimization.application.OptimizationAnalyzerRegistry;
import com.tarunkishore.loom_api.ai.optimization.analyzer.SlowTaskAnalyzer;
import com.tarunkishore.loom_api.ai.optimization.analyzer.RetryHotspotAnalyzer;
import com.tarunkishore.loom_api.ai.optimization.analyzer.FailureHotspotAnalyzer;
import com.tarunkishore.loom_api.ai.optimization.analyzer.DlqHotspotAnalyzer;
import com.tarunkishore.loom_api.ai.optimization.analyzer.DurationImbalanceAnalyzer;
import com.tarunkishore.loom_api.ai.optimization.analyzer.ParallelExecutionObservationAnalyzer;
import com.tarunkishore.loom_api.ai.optimization.support.OptimizationMetrics;
import com.tarunkishore.loom_api.ai.optimization.support.OptimizationCandidateFactory;

import com.loom.capability.TaskType;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

class OptimizationAnalyzersTest {
    private static final Instant START = Instant.parse("2026-10-17T12:00:00Z");
    private static final UUID WORKFLOW = UUID.fromString("10000000-0000-0000-0000-000000000000");

    @Test void slowTaskUsesRecordedActiveTimeNotWallClock() {
        var candidates = new SlowTaskAnalyzer().analyze(context(history(5, 100, 900, 0, false)));
        assertThat(candidates).hasSize(1);
        var candidate = candidates.getFirst();
        assertThat(candidate.taskReferences()).containsExactly("b");
        assertThat(candidate.evidence().activeAttemptMillis()).isEqualTo(4500);
        assertThat(candidate.evidence().averageActiveAttemptMillis()).isEqualTo(900);
        assertThat(candidate.evidence().shareOfRecordedActiveTimePercent()).isEqualTo(90);
        assertThat(candidate.limitation()).contains("not wall-clock");
    }

    @Test void equalDurationsAreNotSlowTaskCandidates() {
        assertThat(new SlowTaskAnalyzer().analyze(context(history(5, 100, 100, 0, false)))).isEmpty();
    }

    @Test void sparseLongTaskCountsInDurationDenominatorButCannotBecomeAdvice() {
        var original = context(history(20, 100, 900, 0, false));
        var runs = new ArrayList<>(original.runs());
        var first = runs.getFirst();
        var tasks = new ArrayList<>(first.tasks());
        tasks.add(task("sparse", 100_000, false, false));
        runs.set(0, new OptimizationContext.Run(first.jobId(), first.status(), first.createdAt(), tasks));
        var definitions = new ArrayList<>(original.tasks());
        definitions.add(structure("sparse", "root"));
        // A: 2,000ms / B: 18,000ms / sparse: 100,000ms. B is 15%, not 90%.
        // Sparse has only one execution, so none of the definitions qualifies for advice.
        assertThat(new SlowTaskAnalyzer().analyze(copy(original, definitions, runs))).isEmpty();
        assertThat(new SlowTaskAnalyzer().analyze(original)).singleElement()
                .satisfies(candidate -> assertThat(candidate.taskReferences()).containsExactly("b"));
    }

    @Test void insufficientSamplesDoNotCreateDurationAdvice() {
        assertThat(new SlowTaskAnalyzer().analyze(context(history(4, 100, 900, 0, false)))).isEmpty();
    }

    @Test void incompleteTimingIsExcludedFromDurationCandidates() {
        var runs = history(5, 100, 900, 0, false);
        var broken = replaceB(runs, 0, new OptimizationContext.TaskRun(UUID.randomUUID(), "b", "COMPLETE", null,
                List.of(new OptimizationContext.Attempt(0, "COMPLETE", START, null, null, null))));
        assertThat(new SlowTaskAnalyzer().analyze(context(broken))).isEmpty();
    }

    @Test void retryRateUsesActualRetriesAndEligibleExecutions() {
        var candidates = new RetryHotspotAnalyzer().analyze(context(history(6, 100, 900, 2, false)));
        assertThat(candidates).hasSize(1);
        var evidence = candidates.getFirst().evidence();
        assertThat(evidence.executionsConsidered()).isEqualTo(6);
        assertThat(evidence.executionsWithRetries()).isEqualTo(2);
        assertThat(evidence.recordedRetries()).isEqualTo(4);
        assertThat(evidence.attemptsConsidered()).isEqualTo(10);
        assertThat(evidence.ratePercent()).isEqualTo(33.33);
    }

    @Test void rareRetryDoesNotCreateCandidate() {
        assertThat(new RetryHotspotAnalyzer().analyze(context(history(10, 100, 900, 2, false)))).isEmpty();
        assertThat(new RetryHotspotAnalyzer().analyze(context(history(5, 100, 900, 1, false)))).isEmpty();
    }

    @Test void failedAttemptHotspotDistinguishesRecoveryFromTerminalFailure() {
        var candidate = new FailureHotspotAnalyzer().analyze(context(history(5, 100, 900, 2, false))).getFirst();
        assertThat(candidate.evidence().executionsWithFailedAttempts()).isEqualTo(2);
        assertThat(candidate.evidence().recoveredExecutions()).isEqualTo(2);
        assertThat(candidate.evidence().terminalFailures()).isZero();
        assertThat(candidate.evidence().errorTypeCounts()).containsEntry("HTTP_STATUS_500", 4);
    }

    @Test void dlqHotspotIsDistinctFromRecoveringRetries() {
        var analyzer = new DlqHotspotAnalyzer();
        assertThat(analyzer.analyze(context(history(5, 100, 900, 2, false)))).isEmpty();
        var evidence = analyzer.analyze(context(history(5, 100, 900, 2, true))).getFirst().evidence();
        assertThat(evidence.deadLetteredExecutions()).isEqualTo(2);
        assertThat(evidence.ratePercent()).isEqualTo(40);
        assertThat(evidence.recoveredExecutions()).isZero();
    }

    @Test void attemptGapsAreExcludedFromRateDenominator() {
        var runs = history(6, 100, 900, 2, false);
        var broken = replaceB(runs, 5, new OptimizationContext.TaskRun(UUID.randomUUID(), "b", "COMPLETE", null,
                List.of(attempt(2, "COMPLETE", 100))));
        var evidence = new RetryHotspotAnalyzer().analyze(context(broken)).getFirst().evidence();
        assertThat(evidence.executionsConsidered()).isEqualTo(5);
        assertThat(evidence.excludedExecutions()).isEqualTo(1);
        assertThat(evidence.ratePercent()).isEqualTo(40);
    }

    @Test void duplicateAttemptNumbersAreIncomplete() {
        var task = new OptimizationContext.TaskRun(UUID.randomUUID(), "b", "COMPLETE", null,
                List.of(attempt(0, "FAILED", 100), attempt(0, "COMPLETE", 100)));
        assertThat(OptimizationMetrics.completeAttempts(task)).isFalse();
    }

    @Test void siblingDurationComparisonUsesPairedCompleteSamples() {
        var candidate = new DurationImbalanceAnalyzer().analyze(context(history(5, 100, 900, 0, false))).getFirst();
        assertThat(candidate.taskReferences()).containsExactly("b", "a");
        assertThat(candidate.evidence().executionsConsidered()).isEqualTo(5);
        assertThat(candidate.evidence().activeAttemptMillis()).isEqualTo(4500);
        assertThat(candidate.evidence().comparisonActiveAttemptMillis()).isEqualTo(500);
        assertThat(candidate.evidence().durationRatio()).isEqualTo(9);
        assertThat(candidate.limitation()).contains("not establish");
    }

    @Test void balancedOrInsufficientSiblingHistoryHasNoImbalance() {
        assertThat(new DurationImbalanceAnalyzer().analyze(context(history(5, 100, 200, 0, false)))).isEmpty();
        assertThat(new DurationImbalanceAnalyzer().analyze(context(history(4, 100, 900, 0, false)))).isEmpty();
    }

    @Test void graphCandidateAlwaysPersistsBusinessIndependenceLimitation() {
        var candidate = new ParallelExecutionObservationAnalyzer().analyze(context(history(5, 100, 100, 0, false))).getFirst();
        assertThat(candidate.classification()).isEqualTo(OptimizationClassification.STRUCTURAL_OBSERVATION);
        assertThat(candidate.type()).isEqualTo(OptimizationType.PARALLEL_EXECUTION_OBSERVATION);
        assertThat(candidate.severity()).isEqualTo(OptimizationSeverity.INFO);
        assertThat(candidate.evidence().sharedPrerequisites()).containsExactly("root");
        assertThat(candidate.limitation()).contains("does not prove", "already", "no speedup");
    }

    @Test void directOrIndirectDependenciesDoNotCreateParallelCandidate() {
        var original = context(history(5, 100, 100, 0, false));
        var chain = copy(original, List.of(structure("root"), structure("a", "root"), structure("b", "a")), original.runs());
        assertThat(new ParallelExecutionObservationAnalyzer().analyze(chain)).isEmpty();
    }

    @Test void diamondJoinDoesNotBecomeSiblingOfItsParents() {
        var original = context(history(5, 100, 100, 0, false));
        var diamond = copy(original, List.of(structure("root"), structure("a", "root"),
                structure("b", "root"), structure("join", "a", "b")), original.runs());
        assertThat(new ParallelExecutionObservationAnalyzer().analyze(diamond)).singleElement()
                .satisfies(candidate -> assertThat(candidate.taskReferences()).containsExactly("a", "b"));
    }

    @Test void unknownHistoricalErrorCodesAreNotCopiedAsArbitraryText() {
        var task = new OptimizationContext.TaskRun(UUID.randomUUID(), "b", "FAILED", null,
                List.of(new OptimizationContext.Attempt(0, "FAILED", START, START.plusMillis(10),
                        "Bearer secret-text", null)));
        assertThat(OptimizationCandidateFactory.evidence(List.of(task), 0, 100.0, 10L, 0.0, null, null, List.of())
                .errorTypeCounts()).containsOnlyKeys("OTHER");
    }

    @Test void uncomputedDurationAndRateMetricsAreUnknownRatherThanZero() {
        var context = context(history(5, 100, 900, 2, true));
        for (var analyzer : List.of(new RetryHotspotAnalyzer(), new FailureHotspotAnalyzer(), new DlqHotspotAnalyzer())) {
            var evidence = analyzer.analyze(context).getFirst().evidence();
            assertThat(evidence.ratePercent()).isEqualTo(40.0);
            assertThat(evidence.activeAttemptMillis()).isNull();
            assertThat(evidence.averageActiveAttemptMillis()).isNull();
            assertThat(evidence.shareOfRecordedActiveTimePercent()).isNull();
        }
        var structural = new ParallelExecutionObservationAnalyzer().analyze(context).getFirst().evidence();
        assertThat(structural.ratePercent()).isNull();
        assertThat(structural.activeAttemptMillis()).isNull();
        assertThat(structural.averageActiveAttemptMillis()).isNull();
        assertThat(structural.shareOfRecordedActiveTimePercent()).isNull();
        assertThat(new SlowTaskAnalyzer().analyze(context).getFirst().evidence().ratePercent()).isNull();
        var imbalance = new DurationImbalanceAnalyzer().analyze(context).getFirst().evidence();
        assertThat(imbalance.ratePercent()).isNull();
        assertThat(imbalance.shareOfRecordedActiveTimePercent()).isNull();
    }

    @Test void genuinelyComputedZeroDurationRemainsZero() {
        var task = task("a", 0, false, false);
        var evidence = OptimizationCandidateFactory.evidence(List.of(task), 0, 0.0, 0L, 0.0, null, null, List.of());
        assertThat(evidence.ratePercent()).isZero();
        assertThat(evidence.activeAttemptMillis()).isZero();
        assertThat(evidence.averageActiveAttemptMillis()).isZero();
        assertThat(evidence.shareOfRecordedActiveTimePercent()).isZero();
    }

    @Test void registryRejectsDuplicateAnalyzerTypes() {
        assertThatThrownBy(() -> new OptimizationAnalyzerRegistry(List.of(new SlowTaskAnalyzer(), new SlowTaskAnalyzer()), new OptimizationContextFingerprint()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");
    }

    @Test void registryCapsCandidatesAndAssignsStableApplicationIds() {
        var original = context(history(5, 100, 900, 2, true));
        var bounded = new OptimizationContext(original.workflowId(), original.workflowVersion(), original.tasks(),
                original.runs(), false, 0, 5, 2, 86400, List.of());
        var registry = registry();
        var facts = registry.analyze(bounded);
        assertThat(facts.candidates()).hasSize(2).extracting(OptimizationCandidate::candidateId).containsExactly("O1", "O2");
        assertThat(registry.analyze(bounded)).isEqualTo(facts);
    }

    @Test void registryHasDeterministicInsufficientAndNoCandidatePaths() {
        assertThat(registry().analyze(context(history(4, 100, 100, 0, false))).outcome()).isEqualTo(OptimizationOutcome.INSUFFICIENT_HISTORY);
        var original = context(history(5, 100, 100, 0, false));
        var chain = copy(original, List.of(structure("root"), structure("a", "root"), structure("b", "a")), original.runs());
        assertThat(registry().analyze(chain).outcome()).isEqualTo(OptimizationOutcome.NO_CANDIDATES);
    }

    @Test void contextOrderingDoesNotChangeFingerprintOrCandidateIds() {
        var original = context(history(5, 100, 900, 2, true));
        List<OptimizationContext.Run> reordered = original.runs().reversed().stream().map(run ->
                new OptimizationContext.Run(run.jobId(), run.status(), run.createdAt(), run.tasks().reversed().stream()
                        .map(task -> new OptimizationContext.TaskRun(task.taskId(), task.logicalId(), task.status(),
                                task.deadLetteredAt(), task.attempts().reversed())).toList())).toList();
        assertThat(registry().analyze(copy(original, original.tasks().reversed(), reordered)))
                .isEqualTo(registry().analyze(original));
    }

    @Test void executionEvidenceChangeCreatesNewFingerprint() {
        var original = context(history(5, 100, 900, 0, false));
        var changed = replaceB(original.runs(), 0, task("b", 901, false, false));
        assertThat(registry().analyze(copy(original, original.tasks(), changed)).evidenceFingerprint())
                .isNotEqualTo(registry().analyze(original).evidenceFingerprint());
    }

    @Test void selectedRunIdentifiersArePreservedInDeterministicOrder() {
        var context = context(history(5, 100, 900, 0, false));
        assertThat(registry().analyze(context).runIds()).containsExactlyElementsOf(
                context.runs().stream().map(OptimizationContext.Run::jobId).sorted().toList());
        assertThat(registry().analyze(copy(context, context.tasks(), context.runs().reversed())).runIds())
                .isEqualTo(registry().analyze(context).runIds());
    }

    @Test void taskDefinitionsPreserveTypedGraphWithSortedImmutableDependencies() {
        var original = context(history(5, 100, 900, 0, false));
        var definitions = List.of(new OptimizationContext.TaskStructure("join", TaskType.DELAY, List.of("b", "a")),
                structure("b", "root"), structure("a", "root"), structure("root"));
        var facts = registry().analyze(copy(original, definitions, original.runs()));
        assertThat(facts.taskDefinitions()).extracting(OptimizationContext.TaskStructure::taskId)
                .containsExactly("a", "b", "join", "root");
        var join = facts.taskDefinitions().get(2);
        assertThat(join.type()).isEqualTo(TaskType.DELAY);
        assertThat(join.dependsOn()).containsExactly("a", "b");
        assertThatThrownBy(() -> facts.taskDefinitions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> join.dependsOn().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static OptimizationAnalyzerRegistry registry() {
        return new OptimizationAnalyzerRegistry(List.of(new SlowTaskAnalyzer(), new RetryHotspotAnalyzer(),
                new FailureHotspotAnalyzer(), new DlqHotspotAnalyzer(), new DurationImbalanceAnalyzer(),
                new ParallelExecutionObservationAnalyzer()), new OptimizationContextFingerprint());
    }

    private static OptimizationContext context(List<OptimizationContext.Run> runs) {
        return new OptimizationContext(WORKFLOW, 0L,
                List.of(structure("root"), structure("a", "root"), structure("b", "root")),
                runs, false, 0, 5, 20, 86400, List.of("No authoritative wall-clock timing"));
    }

    private static OptimizationContext copy(OptimizationContext original,
            List<OptimizationContext.TaskStructure> tasks, List<OptimizationContext.Run> runs) {
        return new OptimizationContext(original.workflowId(), original.workflowVersion(), tasks, runs,
                original.historyTruncated(), original.excludedRuns(), original.minSampleSize(),
                original.maxCandidates(), original.historyWindowSeconds(), original.limitations());
    }

    private static OptimizationContext.TaskStructure structure(String id, String... dependencies) {
        return new OptimizationContext.TaskStructure(id, TaskType.NOOP, List.of(dependencies));
    }

    private static List<OptimizationContext.Run> history(int count, long aMillis, long bMillis, int retryRuns, boolean dlq) {
        return IntStream.range(0, count).mapToObj(index -> new OptimizationContext.Run(
                UUID.nameUUIDFromBytes(("run" + index).getBytes(StandardCharsets.UTF_8)),
                index < retryRuns && dlq ? "FAILED" : "COMPLETE", START.plusSeconds(index),
                List.of(task("a", aMillis, false, false), task("b", bMillis, index < retryRuns, dlq)))).toList();
    }

    private static OptimizationContext.TaskRun task(String id, long millis, boolean retries, boolean dlq) {
        List<OptimizationContext.Attempt> attempts = retries
                ? List.of(attempt(0, "FAILED", millis), attempt(1, "FAILED", millis), attempt(2, dlq ? "FAILED" : "COMPLETE", millis))
                : List.of(attempt(0, "COMPLETE", millis));
        return new OptimizationContext.TaskRun(UUID.nameUUIDFromBytes((id + millis + retries).getBytes(StandardCharsets.UTF_8)),
                id, retries && dlq ? "DEAD_LETTERED" : "COMPLETE", retries && dlq ? START.plusSeconds(10) : null, attempts);
    }

    private static OptimizationContext.Attempt attempt(int number, String status, long millis) {
        return new OptimizationContext.Attempt(number, status, START.plusSeconds(number), START.plusSeconds(number).plusMillis(millis),
                "FAILED".equals(status) ? "HTTP_STATUS_500" : null, null);
    }

    private static List<OptimizationContext.Run> replaceB(List<OptimizationContext.Run> runs, int index, OptimizationContext.TaskRun task) {
        var changed = new ArrayList<>(runs);
        var run = changed.get(index);
        changed.set(index, new OptimizationContext.Run(run.jobId(), run.status(), run.createdAt(), List.of(run.tasks().getFirst(), task)));
        return List.copyOf(changed);
    }
}
