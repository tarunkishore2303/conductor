package com.tarunkishore.loom_api.ai;

import com.tarunkishore.loom_api.exception.AiOutputException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loom.common.model.*;
import com.tarunkishore.loom_api.repository.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;

class FailureContextCollectorTest {
    @Test
    void scheduledRetryRejectedWhileUnattemptedBlockedDescendantIsAllowed() {
        var job = new Job();
        job.setStatus(JobStatus.FAILED);
        var retrying = new Task();
        retrying.setId(UUID.randomUUID());
        retrying.setName("Retrying sibling");
        retrying.setStatus(TaskStatus.PENDING);
        var blocked = new Task();
        blocked.setId(UUID.randomUUID());
        blocked.setName("Blocked descendant");
        blocked.setStatus(TaskStatus.PENDING);
        job.setTasks(List.of(retrying, blocked));
        var failed = new TaskExecution();
        failed.setTask(retrying);
        failed.setStatus(TaskStatus.FAILED);
        failed.setCompletedAt(Instant.now());
        failed.setRetryScheduledAt(Instant.now().plusSeconds(10));
        failed.setErrorMessage("Dependency unavailable");
        when(jobs.findByIdWithTasks(id)).thenReturn(Optional.of(job));
        when(evidence.findForJob(eq(id), any())).thenReturn(List.of(failed));
        assertThatThrownBy(() -> collector.collect(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduled retries");
        retrying.setStatus(TaskStatus.FAILED);
        assertThatThrownBy(() -> collector.collect(id)).isInstanceOf(IllegalStateException.class);
        retrying.setStatus(TaskStatus.DEAD_LETTERED);
        assertThat(collector.collect(id).tasks()).hasSize(2);
    }
    final JobRepository jobs = mock(JobRepository.class);
    final FailureEvidenceRepository evidence = mock(FailureEvidenceRepository.class);
    final FailureContextCollector collector = new FailureContextCollector(jobs, evidence);
    final UUID id = UUID.randomUUID();

    @Test
    void legacyAttemptReportsUnavailableAndRedactsCredentials() {
        var job = new Job();
        job.setId(id);
        job.setStatus(JobStatus.FAILED);
        var task = new Task();
        task.setId(UUID.randomUUID());
        task.setName("Demo");
        task.setStatus(TaskStatus.DEAD_LETTERED);
        task.setMaxRetries(2);
        job.setTasks(List.of(task));
        var attempt = new TaskExecution();
        attempt.setTask(task);
        attempt.setStatus(TaskStatus.DEAD_LETTERED);
        attempt.setStartedAt(Instant.now());
        attempt.setCompletedAt(Instant.now());
        attempt.setErrorMessage("password=secret-value Authorization=Bearer-token");
        when(jobs.findByIdWithTasks(id)).thenReturn(Optional.of(job));
        when(evidence.findForJob(eq(id), any())).thenReturn(List.of(attempt));
        var facts = collector.collect(id);
        assertThat(facts.unavailableEvidence()).anyMatch(s -> s.contains("legacy"));
        assertThat(facts.tasks().getFirst().attempts().getFirst().errorMessage())
                .doesNotContain("secret-value", "Bearer-token");
        assertThat(facts.tasks().getFirst().deadLetteredAt()).isNull();
    }

    @Test
    void successAndInflightRejected() {
        var job = new Job();
        job.setStatus(JobStatus.COMPLETE);
        when(jobs.findByIdWithTasks(id)).thenReturn(Optional.of(job));
        assertThatThrownBy(() -> collector.collect(id)).isInstanceOf(IllegalStateException.class);
        job.setStatus(JobStatus.FAILED);
        var task = new Task();
        task.setStatus(TaskStatus.RUNNING);
        job.setTasks(List.of(task));
        assertThatThrownBy(() -> collector.collect(id)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingEvidenceRejectedInsteadOfInvented() {
        var job = new Job();
        job.setStatus(JobStatus.FAILED);
        var task = new Task();
        task.setStatus(TaskStatus.DEAD_LETTERED);
        job.setTasks(List.of(task));
        when(jobs.findByIdWithTasks(id)).thenReturn(Optional.of(job));
        when(evidence.findForJob(eq(id), any())).thenReturn(List.of());
        assertThatThrownBy(() -> collector.collect(id)).isInstanceOf(AiOutputException.class);
    }

    @Test
    void multipleFailedTasksKeepTheirOwnEvidence() {
        var job = new Job();
        job.setStatus(JobStatus.FAILED);
        var left = new Task();
        left.setId(UUID.randomUUID());
        left.setName("left");
        left.setStatus(TaskStatus.FAILED);
        var right = new Task();
        right.setId(UUID.randomUUID());
        right.setName("right");
        right.setStatus(TaskStatus.DEAD_LETTERED);
        job.setTasks(List.of(left, right));
        var a = new TaskExecution();
        a.setTask(left);
        a.setStatus(TaskStatus.FAILED);
        a.setCompletedAt(Instant.now());
        a.setErrorMessage("left failure");
        var b = new TaskExecution();
        b.setTask(right);
        b.setStatus(TaskStatus.FAILED);
        b.setCompletedAt(Instant.now());
        b.setErrorMessage("right failure");
        when(jobs.findByIdWithTasks(id)).thenReturn(Optional.of(job));
        when(evidence.findForJob(eq(id), any())).thenReturn(List.of(a, b));
        var context = collector.collect(id);
        assertThat(context.tasks()).hasSize(2);
        for (var task : context.tasks())
            assertThat(task.attempts().getFirst().errorMessage())
                    .isEqualTo(task.name() + " failure");
    }

    @Test
    void taskAndAttemptBoundsRejectInsteadOfSilentlyDroppingEvidence() {
        var job = new Job();
        job.setStatus(JobStatus.FAILED);
        var task = new Task();
        task.setStatus(TaskStatus.FAILED);
        job.setTasks(Collections.nCopies(51, task));
        when(jobs.findByIdWithTasks(id)).thenReturn(Optional.of(job));
        assertThatThrownBy(() -> collector.collect(id))
                .isInstanceOf(AiOutputException.class)
                .hasMessageContaining("50");
        verifyNoInteractions(evidence);
        job.setTasks(List.of(task));
        when(evidence.findForJob(eq(id), any()))
                .thenReturn(Collections.nCopies(301, new TaskExecution()));
        assertThatThrownBy(() -> collector.collect(id))
                .isInstanceOf(AiOutputException.class)
                .hasMessageContaining("300");
    }
    @Test
    void successfulOutputChangesDoNotAffectFailureFactsOrIncidentDocuments() {
        var job = new Job();
        job.setId(id);
        job.setStatus(JobStatus.FAILED);
        var failed = new Task();
        failed.setId(UUID.randomUUID());
        failed.setName("failed");
        failed.setStatus(TaskStatus.DEAD_LETTERED);
        var completed = new Task();
        completed.setId(UUID.randomUUID());
        completed.setName("completed");
        completed.setStatus(TaskStatus.COMPLETE);
        job.setTasks(List.of(failed, completed));
        var failure = new TaskExecution();
        failure.setTask(failed);
        failure.setStatus(TaskStatus.FAILED);
        failure.setAttemptNumber(0);
        failure.setErrorType("HTTP_STATUS_500");
        failure.setErrorMessage("HTTP request failed");
        var success = new TaskExecution();
        success.setTask(completed);
        success.setStatus(TaskStatus.COMPLETE);
        success.setAttemptNumber(0);
        failure.setStartedAt(Instant.parse("2026-10-20T12:00:00Z"));
        failure.setCompletedAt(Instant.parse("2026-10-20T12:00:01Z"));
        success.setStartedAt(Instant.parse("2026-10-20T12:00:00Z"));
        success.setCompletedAt(Instant.parse("2026-10-20T12:00:01Z"));
        when(jobs.findByIdWithTasks(id)).thenReturn(Optional.of(job));
        when(evidence.findForJob(eq(id), any())).thenReturn(List.of(failure, success));
        var mapper = JsonMapper.builder().build();
        String before = mapper.writeValueAsString(collector.collect(id));
        var documentsBefore = new IncidentDocumentBuilder().build(collector.collect(id));
        success.setOutputText("OUTPUT_PRIVATE_SENTINEL");
        success.setOutputContentType("text/plain");
        var after = collector.collect(id);
        // Failure-analysis context hashing uses this exact serialized DTO, never the entity.
        assertThat(mapper.writeValueAsString(after)).isEqualTo(before)
                .doesNotContain("OUTPUT_PRIVATE_SENTINEL", "outputText", "outputContentType");
        assertThat(new IncidentDocumentBuilder().build(after)).isEqualTo(documentsBefore);
    }

}
