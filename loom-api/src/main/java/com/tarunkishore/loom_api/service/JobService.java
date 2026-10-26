package com.tarunkishore.loom_api.service;

import com.loom.capability.TaskCapabilityValidators;
import com.loom.capability.TaskDataflowValidator;
import com.loom.capability.TaskConfigCodec;
import com.loom.common.dto.JobSubmitRequest;
import com.loom.common.dto.TaskDefinition;
import com.loom.common.model.*;
import com.tarunkishore.loom_api.repository.JobRepository;
import com.tarunkishore.loom_api.repository.TaskRepository;
import com.tarunkishore.loom_api.dto.JobSummaryResponse;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

@Service
@RequiredArgsConstructor
public class JobService {
    private final TaskDataflowValidator dataflowValidator = new TaskDataflowValidator();


    private final JobRepository jobRepository;
    private final TaskRepository taskRepository;
    private final DAGValidator dagValidator;
    private final JobEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final TaskCapabilityValidators capabilityValidators;
    private final TaskConfigCodec taskConfigCodec;

    @Transactional
    public Job submit(JobSubmitRequest request) {
        return submit(request, null);
    }

    @Transactional
    public Job submit(JobSubmitRequest request, UUID workflowTemplateId) {
        request.tasks().forEach(task -> capabilityValidators.validate(task.type(), task.config()));
        dataflowValidator.validate(request.tasks().stream().map(task ->
                new TaskDataflowValidator.DataflowTask(task.taskId(), task.dependsOn(), task.config())).toList());
        dagValidator.validate(request.tasks());
        meterRegistry.counter("loom.jobs.submitted",
            "failurePolicy", request.failurePolicy().name()).increment();

        Job job = new Job();
        job.setName(request.name());
        job.setWorkflowTemplateId(workflowTemplateId);
        job.setStatus(JobStatus.CREATED);
        job.setFailurePolicy(request.failurePolicy());

        // Map logical taskId -> Task entity for wiring dependencies
        Map<String, Task> taskMap = new LinkedHashMap<>();
        for (TaskDefinition def : request.tasks()) {
            Task task = new Task();
            task.setJob(job);
            task.setName(def.name());
            task.setStatus(TaskStatus.PENDING);
            task.setMaxRetries(def.maxRetries());
            task.setRetryCount(0);
            task.setLogicalId(def.taskId());
            task.setTaskType(def.type());
            task.setTaskConfigJson(taskConfigCodec.encode(def.config()));
            job.getTasks().add(task);
            taskMap.put(def.taskId(), task);
        }

        // Wire dependencies after all tasks created
        for (TaskDefinition def : request.tasks()) {
            Task task = taskMap.get(def.taskId());
            for (String depId : def.dependsOn()) {
                task.getDependencies().add(taskMap.get(depId));
            }
        }

        Job saved = jobRepository.save(job);
        UUID savedId = saved.getId();
        // Publish after DB commit so scheduler sees persisted data
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publishJobCreated(savedId);
            }
        });
        return saved;
    }

    @Transactional(readOnly = true)
    public List<JobSummaryResponse> listJobs(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Run list limit must be between 1 and 100.");
        }
        var page = PageRequest.of(0, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        return jobRepository.findAllBy(page).stream().map(JobSummaryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public Job getJob(UUID jobId) {
        return jobRepository.findByIdWithTasks(jobId)
            .orElseThrow(() -> new NoSuchElementException("Job not found: " + jobId));
    }

    @Transactional
    public Job cancel(UUID jobId) {
        Job job = jobRepository.findByIdWithTasks(jobId)
            .orElseThrow(() -> new NoSuchElementException("Job not found: " + jobId));
        if (job.getStatus() == JobStatus.COMPLETE || job.getStatus() == JobStatus.CANCELLED) {
            throw new IllegalStateException("Job already in terminal state: " + job.getStatus());
        }
        job.setStatus(JobStatus.CANCELLING);
        return jobRepository.save(job);
    }
}
