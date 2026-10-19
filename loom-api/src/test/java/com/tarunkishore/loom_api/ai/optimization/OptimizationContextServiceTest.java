package com.tarunkishore.loom_api.ai.optimization;

import com.tarunkishore.loom_api.ai.optimization.application.OptimizationContextService;
import com.tarunkishore.loom_api.ai.optimization.config.OptimizationProperties;

import com.loom.capability.*;
import com.loom.common.dto.TaskDefinition;
import com.loom.common.model.Job;
import com.loom.common.model.JobStatus;
import com.loom.common.model.Task;
import com.loom.common.model.TaskExecution;
import com.loom.common.model.TaskStatus;
import com.loom.common.model.WorkflowTemplate;
import com.tarunkishore.loom_api.exception.AiOutputException;
import com.tarunkishore.loom_api.repository.WorkflowTemplateRepository;
import com.tarunkishore.loom_api.repository.JobRepository;
import com.tarunkishore.loom_api.repository.TaskRepository;
import com.tarunkishore.loom_api.repository.FailureEvidenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OptimizationContextServiceTest {
    final WorkflowTemplateRepository workflows = mock(WorkflowTemplateRepository.class);
    final JobRepository jobs = mock(JobRepository.class);
    final TaskRepository tasks = mock(TaskRepository.class);
    final FailureEvidenceRepository evidence = mock(FailureEvidenceRepository.class);
    final ObjectMapper mapper = JsonMapper.builder().build();
    final TaskProperties taskProperties = TaskProperties.defaults();
    final TaskConfigCodec codec = new TaskConfigCodec(new TaskCapabilityValidators(List.of(
            new NoOpTaskConfigValidator(),new DelayTaskConfigValidator(taskProperties),
            new HttpRequestTaskConfigValidator(taskProperties),new TransformTaskConfigValidator(taskProperties))));
    final OptimizationContextService service = new OptimizationContextService(workflows,jobs,tasks,evidence,
            OptimizationProperties.defaults(),mapper,codec);
    final UUID workflowId = UUID.randomUUID();
    WorkflowTemplate workflow; Job job; Task task; TaskExecution attempt;

    @BeforeEach void setup() {
        workflow = new WorkflowTemplate(); workflow.setId(workflowId); workflow.setVersion(0L);
        workflow.setDagDefinitionJson(mapper.writeValueAsString(List.of(new TaskDefinition("one","One",List.of(),2))));
        when(workflows.findById(workflowId)).thenReturn(Optional.of(workflow));
        job = new Job(); job.setId(UUID.randomUUID()); job.setStatus(JobStatus.COMPLETE); job.setCreatedAt(Instant.parse("2026-10-01T00:00:00Z"));
        task = new Task(); task.setId(UUID.randomUUID()); task.setJob(job); task.setLogicalId("one"); task.setMaxRetries(2); task.setStatus(TaskStatus.COMPLETE);
        attempt = new TaskExecution(); attempt.setId(UUID.randomUUID()); attempt.setTask(task); attempt.setAttemptNumber(0); attempt.setStatus(TaskStatus.COMPLETE);
        attempt.setStartedAt(job.getCreatedAt()); attempt.setCompletedAt(job.getCreatedAt().plusMillis(20));
        when(jobs.findOptimizationHistory(eq(workflowId),anyList(),any(),any())).thenReturn(List.of(job));
        when(tasks.findOptimizationTasks(anyList(),any())).thenReturn(List.of(task));
        when(tasks.findOptimizationDependencies(anyList(),any())).thenReturn(List.of());
        when(evidence.findOptimizationAttempts(anyList(),any())).thenReturn(List.of(attempt));
    }
    @Test void collectsOnlyBoundedStructuredEvidenceWithoutConfigurationOrRawErrors() {
        attempt.setErrorType("HTTP_STATUS_500"); attempt.setErrorMessage("sensitive body");
        var result = service.collect(workflowId);
        assertThat(result.runs()).hasSize(1);
        assertThat(result.runs().getFirst().tasks().getFirst().logicalId()).isEqualTo("one");
        String json=mapper.writeValueAsString(result);
        assertThat(json).contains("HTTP_STATUS_500").doesNotContain("sensitive body","taskConfigJson");
        verify(tasks).findOptimizationTasks(anyList(),argThat(p -> p.getPageSize()==2501));
        verify(evidence).findOptimizationAttempts(anyList(),argThat(p -> p.getPageSize()==1001));
    }
    @Test void missingHistoricalLogicalIdentityExcludedWithoutGuessingNames() {
        task.setLogicalId(null); task.setName("one");
        assertThat(service.collect(workflowId).excludedRuns()).isEqualTo(1);
    }
    @Test void changedConfigExcludedRatherThanAggregated() {
        workflow.setDagDefinitionJson(mapper.writeValueAsString(List.of(new TaskDefinition("one","One",List.of(),2,TaskType.DELAY,new DelayTaskConfig(3)))));
        task.setTaskType(TaskType.DELAY); task.setTaskConfigJson("{\"durationMillis\":4}");
        assertThat(service.collect(workflowId).runs()).isEmpty();
    }
    @Test void failedJobWithRunningSiblingExcluded() {
        job.setStatus(JobStatus.FAILED); task.setStatus(TaskStatus.RUNNING);
        assertThat(service.collect(workflowId).excludedRuns()).isEqualTo(1);
    }
    @Test void scheduledRetryExcluded() {
        job.setStatus(JobStatus.FAILED); task.setStatus(TaskStatus.FAILED); attempt.setStatus(TaskStatus.FAILED);
        attempt.setRetryScheduledAt(Instant.now());
        assertThat(service.collect(workflowId).runs()).isEmpty();
    }
    @Test void terminalDlqEvidencePreserved() {
        job.setStatus(JobStatus.FAILED); task.setStatus(TaskStatus.DEAD_LETTERED); task.setDeadLetteredAt(Instant.now());
        attempt.setStatus(TaskStatus.FAILED); attempt.setAttemptNumber(2); attempt.setErrorType("HTTP_STATUS_500");
        var fact=service.collect(workflowId).runs().getFirst().tasks().getFirst();
        assertThat(fact.status()).isEqualTo("DEAD_LETTERED"); assertThat(fact.attempts().getFirst().attemptNumber()).isEqualTo(2);
    }
    @Test void unlinkedHistoryProducesDeterministicEmptyContextWithoutBulkReads() {
        when(jobs.findOptimizationHistory(eq(workflowId),anyList(),any(),any())).thenReturn(List.of());
        assertThat(service.collect(workflowId).runs()).isEmpty(); verifyNoInteractions(tasks,evidence);
    }
    @Test void attemptOverflowRejected() {
        when(evidence.findOptimizationAttempts(anyList(),any())).thenReturn(Collections.nCopies(1001,attempt));
        assertThatThrownBy(() -> service.collect(workflowId)).isInstanceOf(AiOutputException.class);
    }
    @Test void missingWorkflowIsNotFound() {
        when(workflows.findById(workflowId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.collect(workflowId)).isInstanceOf(NoSuchElementException.class);
    }
    @Test void malformedErrorTypeIsNotForwarded() {
        attempt.setErrorType("https://private.example/token");
        assertThat(service.collect(workflowId).runs().getFirst().tasks().getFirst().attempts().getFirst().errorType()).isEqualTo("UNCLASSIFIED_ERROR");
    }
}
