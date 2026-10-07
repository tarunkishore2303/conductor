package com.tarunkishore.loom_api.ai.copilot;

import com.loom.common.model.*;
import com.tarunkishore.loom_api.ai.FailureAnalysisService;
import com.tarunkishore.loom_api.ai.SimilarIncidentService;
import com.tarunkishore.loom_api.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CopilotReadServiceTest {
    private final JobRepository jobs = mock(JobRepository.class);
    private final FailureEvidenceRepository evidence = mock(FailureEvidenceRepository.class);
    private final WorkflowTemplateRepository workflows = mock(WorkflowTemplateRepository.class);
    private final FailureAnalysisService analyses = mock(FailureAnalysisService.class);
    private final SimilarIncidentService incidents = mock(SimilarIncidentService.class);
    private final CopilotReadService reads = new CopilotReadService(jobs, evidence, workflows, analyses,
            incidents, JsonMapper.builder().build());
    private final UUID jobId = UUID.randomUUID();
    private Job job;
    private Task failed;
    private Task completed;

    @BeforeEach
    void setup() {
        job = new Job();
        job.setId(jobId);
        job.setName("orders");
        job.setStatus(JobStatus.FAILED);
        failed = task(TaskStatus.DEAD_LETTERED);
        failed.setDeadLetteredAt(Instant.parse("2026-10-07T10:00:04Z"));
        completed = task(TaskStatus.COMPLETE);
        job.setTasks(List.of(failed, completed));
        when(jobs.findByIdWithTasks(jobId)).thenReturn(Optional.of(job));
        when(evidence.findForJob(eq(jobId), any())).thenReturn(List.of(
                attempt(failed, 0, "2026-10-07T10:00:00Z", "2026-10-07T10:00:01Z"),
                attempt(failed, 1, "2026-10-07T10:00:03Z", "2026-10-07T10:00:04Z"),
                attempt(completed, 0, "2026-10-07T10:00:00Z", "2026-10-07T10:00:02Z")));
    }

    private Task task(TaskStatus status) {
        var task = new Task();
        task.setId(UUID.randomUUID());
        task.setName("step");
        task.setStatus(status);
        task.setJob(job);
        task.setMaxRetries(1);
        return task;
    }

    private TaskExecution attempt(Task task, Integer number, String started, String completedAt) {
        var attempt = new TaskExecution();
        attempt.setTask(task);
        attempt.setAttemptNumber(number);
        attempt.setStatus(task.getStatus() == TaskStatus.COMPLETE ? TaskStatus.COMPLETE : TaskStatus.FAILED);
        attempt.setStartedAt(Instant.parse(started));
        attempt.setCompletedAt(Instant.parse(completedAt));
        attempt.setErrorMessage(task == failed ? "HTTP 503 password=secret" : null);
        return attempt;
    }

    @Test
    void countsRecordedRetriesAndObservedSpanWithoutInventingRunCompletion() {
        var facts = reads.getRun(jobId);
        assertThat(facts.recordedAttempts()).isEqualTo(3);
        assertThat(facts.recordedRetries()).isEqualTo(1);
        assertThat(facts.retryHistoryComplete()).isTrue();
        assertThat(facts.observedAttemptSpanMillis()).isEqualTo(4000);
        assertThat(facts.aggregateRecordedTaskDurationMillis()).isEqualTo(4000);
        assertThat(facts.slowestObservedTasks()).hasSize(2);
        assertThat(facts.slowestObservedTasks()).allMatch(t -> t.recordedActiveDurationMillis() == 2000
                && t.shareOfAggregateRecordedTaskDuration() == 0.5);
        assertThat(facts.recordedTimingComplete()).isTrue();
        assertThat(facts.taskStates()).containsEntry("DEAD_LETTERED", 1L).containsEntry("COMPLETE", 1L);
        assertThat(facts.unavailableEvidence()).anyMatch(s -> s.contains("not exact workflow"));
        verifyNoInteractions(analyses, incidents, workflows);
        verify(jobs, never()).save(any());
        verify(evidence, never()).save(any());
    }

    @Test
    void failedTasksIncludeDlqAndAttemptsSanitizeErrors() {
        var tasks = reads.getTasks(jobId, true);
        assertThat(tasks.tasks()).hasSize(1);
        assertThat(tasks.tasks().getFirst().deadLetteredAt()).isEqualTo(failed.getDeadLetteredAt());
        var attempts = reads.getTaskAttempts(jobId, failed.getId());
        assertThat(attempts.attempts()).hasSize(2);
        assertThat(attempts.attempts().getFirst().errorMessage()).contains("[REDACTED]").doesNotContain("secret");
        assertThat(attempts.attempts().getFirst().attemptNumber()).isZero();
    }

    @Test
    void taskOutsideScopeCannotReadAttempts() {
        assertThatThrownBy(() -> reads.getTaskAttempts(jobId, UUID.randomUUID()))
                .isInstanceOf(CopilotReadService.ToolReadException.class)
                .satisfies(e -> assertThat(((CopilotReadService.ToolReadException) e).code()).isEqualTo("OUT_OF_SCOPE"));
        verifyNoInteractions(evidence);
    }

    @Test
    void unavailableAndLegacyAttemptsAreExplicit() {
        when(evidence.findForJob(eq(jobId), any())).thenReturn(List.of());
        assertThat(reads.getRun(jobId).observedAttemptSpanMillis()).isNull();
        assertThat(reads.getRun(jobId).unavailableEvidence()).anyMatch(s -> s.contains("No persisted"));
        when(evidence.findForJob(eq(jobId), any())).thenReturn(List.of(
                attempt(failed, null, "2026-10-07T10:00:00Z", "2026-10-07T10:00:01Z")));
        assertThat(reads.getRun(jobId).unavailableEvidence()).anyMatch(s -> s.contains("Legacy"));
        assertThat(reads.getRun(jobId).retryHistoryComplete()).isFalse();
        assertThat(reads.getRun(jobId).recordedRetries()).isZero();
    }

    @Test
    void gappedHistoryIsIncompleteAndSnapshotIsImmutableSanitizedEvidence() {
        when(evidence.findForJob(eq(jobId), any())).thenReturn(List.of(
                attempt(failed, 0, "2026-10-07T10:00:00Z", "2026-10-07T10:00:01Z"),
                attempt(failed, 2, "2026-10-07T10:00:03Z", "2026-10-07T10:00:04Z")));
        var facts = reads.getRun(jobId);
        assertThat(facts.recordedRetries()).isEqualTo(1);
        assertThat(facts.retryHistoryComplete()).isFalse();
        var snapshot = reads.readSnapshot(jobId);
        var task = snapshot.tasks().stream().filter(t -> failed.getId().equals(t.taskId())).findFirst().orElseThrow();
        assertThat(task.attempts().getFirst().errorMessage()).doesNotContain("secret");
        assertThatThrownBy(() -> snapshot.tasks().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> task.attempts().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void activeAttemptCannotProduceFinishedSpan() {
        var active = attempt(failed, 0, "2026-10-07T10:00:00Z", "2026-10-07T10:00:01Z");
        active.setCompletedAt(null);
        active.setStatus(TaskStatus.RUNNING);
        when(evidence.findForJob(eq(jobId), any())).thenReturn(List.of(active));
        assertThat(reads.getRun(jobId).observedAttemptSpanMillis()).isNull();
        assertThat(reads.getRun(jobId).recordedTimingComplete()).isFalse();
        assertThat(reads.getRun(jobId).slowestObservedTasks()).isEmpty();
    }

    @Test
    void negativeAttemptTimingsAreUnavailableRatherThanNegativeTaskDurations() {
        when(evidence.findForJob(eq(jobId), any())).thenReturn(List.of(
                attempt(failed, 0, "2026-10-07T10:00:03Z", "2026-10-07T10:00:01Z")));
        var facts = reads.getRun(jobId);
        assertThat(facts.aggregateRecordedTaskDurationMillis()).isZero();
        assertThat(facts.recordedTimingComplete()).isFalse();
        assertThat(facts.slowestObservedTasks()).isEmpty();
    }

    @Test
    void attemptAndTaskLimitsFailWithoutDroppingEvidence() {
        when(evidence.findForJob(eq(jobId), any())).thenReturn(java.util.Collections.nCopies(301,
                attempt(failed, 0, "2026-10-07T10:00:00Z", "2026-10-07T10:00:01Z")));
        assertThatThrownBy(() -> reads.getRun(jobId)).isInstanceOf(CopilotReadService.ToolReadException.class);
        job.setTasks(java.util.Collections.nCopies(51, failed));
        assertThatThrownBy(() -> reads.getTasks(jobId, false)).isInstanceOf(CopilotReadService.ToolReadException.class);
    }

    @Test
    void exactStoredAnalysisUsesReadOnlyGetterAndChecksRunOwnership() {
        UUID analysisId = UUID.randomUUID();
        var result = new FailureAnalysisService.Analysis(analysisId, UUID.randomUUID(), null, null,
                "fake", Instant.now(), 1, "hash");
        when(analyses.getById(analysisId)).thenReturn(result);
        assertThatThrownBy(() -> reads.getFailureAnalysis(jobId, analysisId))
                .isInstanceOf(CopilotReadService.ToolReadException.class);
        verify(analyses, never()).analyze(any());
        verify(analyses, never()).get(any());
    }

    @Test
    void similarRetrievalNeverSynthesizesOrIndexesAndBoundsTopK() {
        reads.getSimilarIncidents(jobId, 3);
        verify(incidents).retrieve(jobId, 3);
        verify(incidents, never()).synthesize(any(), anyInt());
        assertThatThrownBy(() -> reads.getSimilarIncidents(jobId, 6))
                .isInstanceOf(CopilotReadService.ToolReadException.class);
    }
}
