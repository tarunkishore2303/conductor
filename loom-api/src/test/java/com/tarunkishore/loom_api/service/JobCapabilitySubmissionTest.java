package com.tarunkishore.loom_api.service;

import com.loom.capability.*;
import com.loom.common.dto.JobSubmitRequest;
import com.loom.common.dto.TaskDefinition;
import com.loom.common.model.Job;
import com.loom.common.model.TaskStatus;
import com.tarunkishore.loom_api.repository.JobRepository;
import com.tarunkishore.loom_api.repository.TaskRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Direct submission must enforce the same capabilities as stored-template submission. */
class JobCapabilitySubmissionTest {
    final TaskProperties properties = TaskProperties.defaults();
    final TaskCapabilityValidators validators = new TaskCapabilityValidators(List.of(new NoOpTaskConfigValidator(),
            new DelayTaskConfigValidator(properties), new HttpRequestTaskConfigValidator(properties),
            new TransformTaskConfigValidator(properties)));
    final TaskConfigCodec codec = new TaskConfigCodec(validators);
    final JobRepository jobs = mock(JobRepository.class);
    final TaskRepository tasks = mock(TaskRepository.class);
    final JobEventPublisher publisher = mock(JobEventPublisher.class);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final JobService service = new JobService(jobs, tasks, new DAGValidator(), publisher, meters, validators, codec);

    @Test void invalidCapabilityRejectedBeforePersistenceOrExecutionPublication() {
        var request = new JobSubmitRequest("invalid", List.of(new TaskDefinition("wait", "Wait", List.of(), 0,
                TaskType.DELAY, new DelayTaskConfig(-1))), null);
        assertThatThrownBy(() -> service.submit(request)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jobs, tasks, publisher);
        assertThat(meters.find("loom.jobs.submitted").counter()).isNull();
    }
    @Test void missingCapabilityConfigRejectedBeforePersistence() {
        var request = new JobSubmitRequest("missing", List.of(new TaskDefinition("http", "HTTP", List.of(), 0,
                TaskType.HTTP_REQUEST, null)), null);
        assertThatThrownBy(() -> service.submit(request)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jobs, tasks, publisher);
    }
    @Test void typedConfigPersistedAndJobEventPublishedOnlyAfterCommit() {
        var config = new DelayTaskConfig(3);
        var request = new JobSubmitRequest("typed", List.of(new TaskDefinition("wait", "Wait", List.of(), 2,
                TaskType.DELAY, config)), null);
        UUID jobId = UUID.randomUUID();
        when(jobs.save(any())).thenAnswer(invocation -> {
            Job job = invocation.getArgument(0);
            job.setId(jobId);
            return job;
        });
        TransactionSynchronizationManager.initSynchronization();
        try {
            var job = service.submit(request);
            var task = job.getTasks().iterator().next();
            assertThat(task.getTaskType()).isEqualTo(TaskType.DELAY);
            assertThat(codec.decode(task.getTaskType(), task.getTaskConfigJson())).isEqualTo(config);
            assertThat(task.getMaxRetries()).isEqualTo(2);
            assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
            verifyNoInteractions(publisher);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            verify(publisher).publishJobCreated(jobId);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
    @Test void legacyDefinitionPersistsNoopTypeAndEmptyConfiguration() {
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var request = new JobSubmitRequest("legacy", List.of(new TaskDefinition("a", "A", List.of(), 0)), null);
        TransactionSynchronizationManager.initSynchronization();
        try {
            var task = service.submit(request).getTasks().iterator().next();
            assertThat(task.getTaskType()).isEqualTo(TaskType.NOOP);
            assertThat(task.getTaskConfigJson()).isEqualTo("{}");
            verifyNoInteractions(publisher);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
