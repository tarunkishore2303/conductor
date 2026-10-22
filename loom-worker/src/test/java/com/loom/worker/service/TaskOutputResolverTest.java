package com.loom.worker.service;

import com.loom.capability.TaskProperties;
import com.loom.capability.TransformTaskConfig;
import com.loom.common.model.Job;
import com.loom.common.model.Task;
import com.loom.common.model.TaskExecution;
import com.loom.common.model.TaskStatus;
import com.loom.worker.exception.TaskExecutionException;
import com.loom.worker.repository.TaskExecutionJpaRepository;
import com.loom.worker.repository.WorkerTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskOutputResolverTest {
    private final WorkerTaskRepository tasks = mock(WorkerTaskRepository.class);
    private final TaskExecutionJpaRepository executions = mock(TaskExecutionJpaRepository.class);
    private final TaskOutputResolver resolver = new TaskOutputResolver(tasks, executions, TaskProperties.defaults());
    private final Task current = new Task();
    private final Task source = new Task();
    private final TaskExecution success = new TaskExecution();
    private final TransformTaskConfig reference = new TransformTaskConfig("JSON_SELECT", null, List.of("id"), Map.of(), "fetch");

    @BeforeEach void setup() {
        var job = new Job(); job.setId(UUID.randomUUID());
        current.setId(UUID.randomUUID()); current.setJob(job); current.setLogicalId("select");
        source.setId(UUID.randomUUID()); source.setJob(job); source.setLogicalId("fetch"); source.setStatus(TaskStatus.COMPLETE);
        success.setStatus(TaskStatus.COMPLETE); success.setOutputText("{\"id\":42}"); success.setOutputContentType("application/json");
    }

    @Test void staticInputDoesNotQueryOutputs() throws Exception {
        assertThat(resolver.resolve(current, new TransformTaskConfig("JSON_SELECT", "{}", List.of(), Map.of()))).isNull();
        verifyNoInteractions(tasks, executions);
    }

    @Test void resolvesExactlyOneSuccessfulDirectDependencyWithinPersistedJob() throws Exception {
        available();
        assertThat(resolver.resolve(current, reference)).isEqualTo(new ResolvedTaskInput("fetch", "{\"id\":42}", "application/json"));
        verify(tasks).findDirectInputTasks(current.getId(), current.getJob().getId(), "fetch");
        verify(executions).findByTaskIdAndStatusOrderByAttemptNumberAsc(source.getId(), TaskStatus.COMPLETE, PageRequest.of(0, 2));
    }

    @Test void rejectsMissingOrCrossJobOrNonDirectSource() {
        when(tasks.findDirectInputTasks(current.getId(), current.getJob().getId(), "fetch")).thenReturn(List.of());
        rejected("INVALID_DATAFLOW_REFERENCE");
        verifyNoInteractions(executions);
    }

    @Test void rejectsSelfReferenceWithoutQueries() {
        current.setLogicalId("fetch"); rejected("INVALID_DATAFLOW_REFERENCE");
        verifyNoInteractions(tasks, executions);
    }

    @Test void rejectsDuplicateLogicalSources() {
        when(tasks.findDirectInputTasks(current.getId(), current.getJob().getId(), "fetch")).thenReturn(List.of(source, source));
        rejected("INVALID_DATAFLOW_REFERENCE");
    }

    @Test void rejectsUnsuccessfulUpstreamTask() {
        when(tasks.findDirectInputTasks(current.getId(), current.getJob().getId(), "fetch")).thenReturn(List.of(source));
        source.setStatus(TaskStatus.DEAD_LETTERED); rejected("UPSTREAM_OUTPUT_UNAVAILABLE");
        verifyNoInteractions(executions);
    }

    @Test void rejectsMissingSuccessfulAttempt() {
        available(); when(executions.findByTaskIdAndStatusOrderByAttemptNumberAsc(source.getId(), TaskStatus.COMPLETE, PageRequest.of(0, 2))).thenReturn(List.of());
        rejected("UPSTREAM_OUTPUT_UNAVAILABLE");
    }

    @Test void rejectsMultipleSuccessfulAttemptsRatherThanGuessing() {
        available(); when(executions.findByTaskIdAndStatusOrderByAttemptNumberAsc(source.getId(), TaskStatus.COMPLETE, PageRequest.of(0, 2))).thenReturn(List.of(success, success));
        rejected("UPSTREAM_OUTPUT_UNAVAILABLE");
    }

    @Test void rejectsMissingCapturedOutput() {
        available(); success.setOutputText(null); rejected("TASK_OUTPUT_MISSING");
    }

    @Test void rejectsTextForJsonConsumer() {
        available(); success.setOutputContentType("text/plain"); rejected("INCOMPATIBLE_TASK_OUTPUT");
    }

    @Test void rejectsMultibyteOversizedOutputWithoutIncludingContentInError() {
        available(); success.setOutputText("界".repeat(22000)); rejected("INCOMPATIBLE_TASK_OUTPUT");
    }

    private void available() {
        when(tasks.findDirectInputTasks(current.getId(), current.getJob().getId(), "fetch")).thenReturn(List.of(source));
        when(executions.findByTaskIdAndStatusOrderByAttemptNumberAsc(source.getId(), TaskStatus.COMPLETE, PageRequest.of(0, 2))).thenReturn(List.of(success));
    }

    private void rejected(String code) {
        assertThatThrownBy(() -> resolver.resolve(current, reference)).isInstanceOfSatisfying(TaskExecutionException.class,
                error -> assertThat(error.errorType()).isEqualTo(code)).hasMessageNotContaining("界");
    }
}
