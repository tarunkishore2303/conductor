package com.tarunkishore.loom_api.ai;

import com.tarunkishore.loom_api.exception.AiOutputException;

import com.loom.capability.TaskCapabilityValidators;
import com.loom.capability.TaskDataflowValidator;
import com.loom.capability.TaskType;
import com.loom.common.dto.TaskDefinition;
import com.loom.common.model.FailurePolicy;
import com.tarunkishore.loom_api.dto.WorkflowTemplateRequest;
import com.tarunkishore.loom_api.service.DAGValidator;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class WorkflowProposalValidator {
    private final TaskDataflowValidator dataflowValidator = new TaskDataflowValidator();

    private final DAGValidator dagValidator;
    private final TaskCapabilityValidators capabilities;

    public WorkflowProposalValidator(DAGValidator dagValidator, TaskCapabilityValidators capabilities) {
        this.dagValidator = dagValidator;
        this.capabilities = capabilities;
    }

    public WorkflowTemplateRequest map(GeneratedWorkflow workflow) {
        if (workflow == null) throw new AiOutputException("Workflow must be present");
        field(workflow.name(), 255, "Workflow name");
        if (workflow.description() != null && workflow.description().length() > 2000) {
            throw new AiOutputException("Workflow description exceeds 2000 characters");
        }
        if (workflow.tasks() == null || workflow.tasks().isEmpty() || workflow.tasks().size() > 50) {
            throw new AiOutputException("Workflow must contain 1 to 50 tasks");
        }
        var tasks = new ArrayList<TaskDefinition>();
        for (var task : workflow.tasks()) {
            if (task == null) throw new AiOutputException("Task must be present");
            field(task.identifier(), 64, "Task identifier");
            field(task.name(), 255, "Task name");
            if (!task.identifier().matches("[A-Za-z][A-Za-z0-9_-]*")) {
                throw new AiOutputException("Invalid task identifier");
            }
            TaskType type;
            try {
                type = taskType(task.type());
                capabilities.validate(type, task.config());
            } catch (IllegalArgumentException invalid) {
                throw new AiOutputException("Invalid task capability: " + invalid.getMessage());
            }
            if (task.dependencies() == null || task.dependencies().size() > 50) {
                throw new AiOutputException("Dependencies must be an array of at most 50 identifiers");
            }
            for (String dependency : task.dependencies()) field(dependency, 64, "Dependency identifier");
            if (task.maxRetries() == null || task.maxRetries() < 0 || task.maxRetries() > 5) {
                throw new AiOutputException("maxRetries must be between 0 and 5");
            }
            tasks.add(new TaskDefinition(task.identifier(), task.name(), task.dependencies(), task.maxRetries(), type, task.config()));
        }
        return new WorkflowTemplateRequest(workflow.name(), workflow.description(), tasks, FailurePolicy.FAIL_FAST);
    }

    public List<String> validate(WorkflowTemplateRequest workflow) {
        try {
            workflow.tasks().forEach(task -> capabilities.validate(task.type(), task.config()));
            dataflowValidator.validate(workflow.tasks().stream().map(task ->
                    new TaskDataflowValidator.DataflowTask(task.taskId(), task.dependsOn(), task.config())).toList());
            dagValidator.validate(workflow.tasks());
            return List.of();
        } catch (IllegalArgumentException e) {
            return List.of(e.getMessage());
        }
    }

    private static void field(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new AiOutputException(label + " must contain 1 to " + max + " characters");
        }
    }
    private static TaskType taskType(String value) {
        try { return TaskType.valueOf(value == null ? "" : value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Unsupported task type"); }
    }

}
