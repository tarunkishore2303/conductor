package com.tarunkishore.loom_api.ai.summary;

import com.tarunkishore.loom_api.ai.AiOutputException;
import com.tarunkishore.loom_api.ai.FailureAnalysisService;
import com.tarunkishore.loom_api.ai.copilot.CopilotReadService;
import com.tarunkishore.loom_api.ai.copilot.CopilotReadService.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionSummaryContextServiceTest {
    private final CopilotReadService reads = mock(CopilotReadService.class);
    private final FailureAnalysisService analyses = mock(FailureAnalysisService.class);
    private final ExecutionSummaryContextService service = new ExecutionSummaryContextService(
            reads, analyses, JsonMapper.builder().build());
    private final UUID job = UUID.randomUUID();
    private final UUID task = UUID.randomUUID();
    private final Instant start = Instant.parse("2026-10-07T10:00:00Z");

    @BeforeEach
    void setup() {
        when(analyses.get(job)).thenThrow(new NoSuchElementException("no analysis"));
        snapshot("COMPLETE", List.of(task("COMPLETE", List.of(attempt(0, 0, 1000)))));
    }

    private Attempt attempt(Integer number, long from, long to) {
        return new Attempt(number, "COMPLETE", start.plusMillis(from), start.plusMillis(to), null, null, null);
    }

    private TaskSnapshot task(String status, List<Attempt> attempts) {
        return new TaskSnapshot(task, "orders", status, 2,
                "DEAD_LETTERED".equals(status) ? start.plusSeconds(4) : null, attempts);
    }

    private void snapshot(String status, List<TaskSnapshot> tasks) {
        when(reads.readSnapshot(job)).thenReturn(new RunSnapshot(job, "run", status, start,
                start.plusSeconds(10), tasks, List.of("Task logs are unavailable.")));
    }

    @Test void completeCountsAndObservedTimingComeFromOneReadOnlySnapshot() {
        var facts = service.collect(job);
        assertThat(facts.taskCount()).isEqualTo(1);
        assertThat(facts.completedTasks()).isEqualTo(1);
        assertThat(facts.observedAttemptSpanMillis()).isEqualTo(1000);
        assertThat(facts.aggregateRecordedTaskDurationMillis()).isEqualTo(1000);
        assertThat(facts.retryHistoryComplete()).isTrue();
        verify(reads, times(1)).readSnapshot(job);
        verifyNoInteractions(analyses);
    }

    @Test void failedRunCountsSeparateFailedDlqCancelledAndBlocked() {
        snapshot("FAILED", List.of(task("DEAD_LETTERED", List.of(attempt(0, 0, 1000))),
                new TaskSnapshot(UUID.randomUUID(), "failed", "FAILED", 0, null, List.of(attempt(0, 0, 500))),
                new TaskSnapshot(UUID.randomUUID(), "cancelled", "CANCELLED", 0, null, List.of()),
                new TaskSnapshot(UUID.randomUUID(), "blocked", "PENDING", 0, null, List.of())));
        var facts = service.collect(job);
        assertThat(facts.failedTasks()).isEqualTo(1);
        assertThat(facts.deadLetteredTasks()).isEqualTo(1);
        assertThat(facts.cancelledTasks()).isEqualTo(1);
        assertThat(facts.blockedPendingTasks()).isEqualTo(1);
        assertThat(facts.failureAnalysisId()).isNull();
        assertThat(facts.unavailableEvidence()).anyMatch(s -> s.contains("does not generate"));
        verify(analyses, never()).analyze(any());
    }

    @Test void runningRunIsRejected() {
        snapshot("RUNNING", List.of(task("COMPLETE", List.of(attempt(0, 0, 1000)))));
        assertThatThrownBy(() -> service.collect(job)).isInstanceOf(IllegalStateException.class);
    }

    @Test void runningTaskIsRejectedEvenIfJobAlreadyFailed() {
        snapshot("FAILED", List.of(task("RUNNING", List.of())));
        assertThatThrownBy(() -> service.collect(job)).isInstanceOf(IllegalStateException.class);
    }

    @Test void runningAttemptIsRejected() {
        snapshot("FAILED", List.of(task("FAILED", List.of(
                new Attempt(0, "RUNNING", start, null, null, null, null)))));
        assertThatThrownBy(() -> service.collect(job)).isInstanceOf(IllegalStateException.class);
    }

    @Test void pendingTaskWithRecordedRetryIsNotSettled() {
        snapshot("FAILED", List.of(task("PENDING", List.of(
                new Attempt(0, "FAILED", start, start.plusSeconds(1), "Timeout", "timeout", start.plusSeconds(2))))));
        assertThatThrownBy(() -> service.collect(job)).isInstanceOf(IllegalStateException.class);
    }

    @Test void failedLatestAttemptWithScheduledRetryIsNotSettled() {
        snapshot("FAILED", List.of(task("FAILED", List.of(
                new Attempt(0, "FAILED", start, start.plusSeconds(1), "Timeout", "timeout", start.plusSeconds(2))))));
        assertThatThrownBy(() -> service.collect(job)).isInstanceOf(IllegalStateException.class);
    }

    @Test void completeRunWithUnfinishedTaskIsRejected() {
        snapshot("COMPLETE", List.of(task("PENDING", List.of())));
        assertThatThrownBy(() -> service.collect(job)).isInstanceOf(IllegalStateException.class);
    }

    @Test void legacyAttemptNumberDoesNotInventRetries() {
        snapshot("COMPLETE", List.of(task("COMPLETE", List.of(attempt(null, 0, 1000)))));
        var facts = service.collect(job);
        assertThat(facts.recordedRetries()).isZero();
        assertThat(facts.retryHistoryComplete()).isFalse();
        assertThat(facts.unavailableEvidence()).anyMatch(s -> s.contains("Legacy"));
    }

    @Test void gappedHistoryCountsOnlyRecordedNoninitialAttempts() {
        snapshot("COMPLETE", List.of(task("COMPLETE", List.of(attempt(0, 0, 1000), attempt(2, 2000, 3000)))));
        var facts = service.collect(job);
        assertThat(facts.recordedRetries()).isEqualTo(1);
        assertThat(facts.retryHistoryComplete()).isFalse();
    }

    @Test void missingOrNegativeTimingsDoNotProduceDuration() {
        snapshot("COMPLETE", List.of(task("COMPLETE", List.of(
                new Attempt(0, "COMPLETE", start, null, null, null, null), attempt(1, 2000, 1000)))));
        var facts = service.collect(job);
        assertThat(facts.observedAttemptSpanMillis()).isNull();
        assertThat(facts.aggregateRecordedTaskDurationMillis()).isZero();
        assertThat(facts.recordedTimingComplete()).isFalse();
        assertThat(facts.slowestObservedTasks()).isEmpty();
    }

    @Test void slowTaskSharesUseSumOfActiveAttemptsNotWallRuntime() {
        snapshot("COMPLETE", List.of(task("COMPLETE", List.of(attempt(0, 0, 1000), attempt(1, 5000, 6000))),
                new TaskSnapshot(UUID.randomUUID(), "other", "COMPLETE", 0, null, List.of(attempt(0, 0, 2000)))));
        var facts = service.collect(job);
        assertThat(facts.observedAttemptSpanMillis()).isEqualTo(6000);
        assertThat(facts.aggregateRecordedTaskDurationMillis()).isEqualTo(4000);
        assertThat(facts.slowestObservedTasks()).allMatch(t -> t.durationMillis() == 2000 && t.shareOfAggregate() == 0.5);
    }

    @Test void fingerprintExcludesUpdatedAtAndIsIndependentOfTaskAndAttemptOrder() {
        var other = new TaskSnapshot(UUID.randomUUID(), "other", "COMPLETE", 0, null, List.of(attempt(0, 0, 300)));
        snapshot("COMPLETE", List.of(task("COMPLETE", List.of(attempt(1, 2000, 3000), attempt(0, 0, 1000))), other));
        String fingerprint = service.collect(job).evidenceFingerprint();
        when(reads.readSnapshot(job)).thenReturn(new RunSnapshot(job, "run", "COMPLETE", start,
                start.plusSeconds(999), List.of(other, task("COMPLETE", List.of(attempt(0, 0, 1000), attempt(1, 2000, 3000)))),
                List.of("Task logs are unavailable.")));
        assertThat(service.collect(job).evidenceFingerprint()).isEqualTo(fingerprint).hasSize(64);
    }

    @Test void changedErrorInvalidatesFingerprintEvenWhenAggregateCountsStaySame() {
        String fingerprint = service.collect(job).evidenceFingerprint();
        snapshot("COMPLETE", List.of(task("COMPLETE", List.of(
                new Attempt(0, "COMPLETE", start, start.plusSeconds(1), "Timeout", "changed evidence", null)))));
        assertThat(service.collect(job).evidenceFingerprint()).isNotEqualTo(fingerprint);
    }

    @Test void optionalStoredFailureAnalysisIsLinkedWithoutRegeneration() {
        snapshot("FAILED", List.of(task("DEAD_LETTERED", List.of(attempt(0, 0, 1000)))));
        UUID id = UUID.randomUUID();
        doReturn(new FailureAnalysisService.Analysis(id, job, null, null, "fake",
                start, 1, "hash")).when(analyses).get(job);
        assertThat(service.collect(job).failureAnalysisId()).isEqualTo(id);
        verify(analyses, never()).analyze(any());
    }

    @Test void boundsRejectOversizedSnapshots() {
        snapshot("COMPLETE", java.util.Collections.nCopies(51, task("COMPLETE", List.of(attempt(0, 0, 1)))));
        assertThatThrownBy(() -> service.collect(job)).isInstanceOf(AiOutputException.class);
        snapshot("COMPLETE", List.of(task("COMPLETE", java.util.Collections.nCopies(301, attempt(0, 0, 1)))));
        assertThatThrownBy(() -> service.collect(job)).isInstanceOf(AiOutputException.class);
    }

    @Test void missingLegacyAttemptsMarkTimingAndRetryTotalsIncomplete() {
        snapshot("COMPLETE", List.of(task("COMPLETE", List.of())));
        var facts = service.collect(job);
        assertThat(facts.recordedTimingComplete()).isFalse();
        assertThat(facts.retryHistoryComplete()).isFalse();
        assertThat(facts.observedAttemptSpanMillis()).isNull();
    }
}
