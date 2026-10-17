package com.tarunkishore.loom_api.service;

import com.loom.capability.*;
import com.loom.common.dto.TaskDefinition;
import com.loom.common.model.FailurePolicy;
import com.loom.common.model.WorkflowTemplate;
import com.tarunkishore.loom_api.dto.WorkflowTemplateRequest;
import com.tarunkishore.loom_api.repository.WorkflowTemplateRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskCapabilityValidationTest {
    final TaskProperties properties = TaskProperties.defaults();
    final TaskCapabilityValidators validators = new TaskCapabilityValidators(List.of(new NoOpTaskConfigValidator(),
            new DelayTaskConfigValidator(properties), new HttpRequestTaskConfigValidator(properties),
            new TransformTaskConfigValidator(properties)));
    final WorkflowTemplateRepository repository = mock(WorkflowTemplateRepository.class);
    final JobService jobs = mock(JobService.class);
    final JsonMapper mapper = JsonMapper.builder().build();
    final WorkflowService service = new WorkflowService(repository, jobs, new DAGValidator(), mapper, validators);

    TaskDefinition task(String id, TaskType type, TaskConfig config, String... dependencies) {
        return new TaskDefinition(id, id, List.of(dependencies), 2, type, config);
    }
    WorkflowTemplateRequest request(TaskDefinition... tasks) {
        return new WorkflowTemplateRequest("Capabilities", "Static independent inputs", List.of(tasks), FailurePolicy.FAIL_FAST);
    }
    @Test void validMixedDagPersistsTypedConfiguration() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.createTemplate(request(
                task("http", TaskType.HTTP_REQUEST, new HttpRequestTaskConfig("GET", "https://example.test/orders", 1000, null, null)),
                task("delay", TaskType.DELAY, new DelayTaskConfig(1), "http"),
                task("transform", TaskType.TRANSFORM, new TransformTaskConfig("JSON_SELECT", "{\"id\":1}", List.of("id"), Map.of()), "delay")));
        var parsed = service.parseTasks(result);
        assertThat(parsed).extracting(TaskDefinition::type).containsExactly(TaskType.HTTP_REQUEST, TaskType.DELAY, TaskType.TRANSFORM);
        assertThat(parsed.get(1).config()).isEqualTo(new DelayTaskConfig(1));
        verifyNoInteractions(jobs);
    }
    @Test void invalidHttpNeverPersists() {
        assertThatThrownBy(() -> service.createTemplate(request(task("a", TaskType.HTTP_REQUEST,
                new HttpRequestTaskConfig("DELETE", "https://example.test", 1000, null, null)))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, jobs);
    }
    @Test void invalidDelayNeverPersists() {
        assertThatThrownBy(() -> service.createTemplate(request(task("a", TaskType.DELAY, new DelayTaskConfig(30001)))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, jobs);
    }
    @Test void invalidTransformNeverPersists() {
        assertThatThrownBy(() -> service.createTemplate(request(task("a", TaskType.TRANSFORM,
                new TransformTaskConfig("JAVASCRIPT", "{}", List.of(), Map.of())))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, jobs);
    }
    @Test void missingConfigurationNeverPersists() {
        assertThatThrownBy(() -> service.createTemplate(request(task("a", TaskType.DELAY, null))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, jobs);
    }
    @Test void configurationTypeMismatchNeverPersists() {
        assertThatThrownBy(() -> service.createTemplate(request(task("a", TaskType.DELAY, new NoOpTaskConfig()))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, jobs);
    }
    @Test void mixedCycleStillUsesDagValidator() {
        assertThatThrownBy(() -> service.createTemplate(request(
                task("a", TaskType.NOOP, new NoOpTaskConfig(), "b"), task("b", TaskType.DELAY, new DelayTaskConfig(0), "a"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cycle");
        verifyNoInteractions(repository, jobs);
    }
    @Test void historicalTemplateWithoutTypeRemainsNoop() {
        var template = new WorkflowTemplate();
        template.setDagDefinitionJson("[{\"taskId\":\"a\",\"name\":\"A\",\"dependsOn\":[],\"maxRetries\":0}]");
        var parsed = service.parseTasks(template);
        assertThat(parsed.getFirst().type()).isEqualTo(TaskType.NOOP);
        assertThat(parsed.getFirst().config()).isEqualTo(new NoOpTaskConfig());
    }
    @Test void unknownTaskTypeIsRejectedDuringDeserialization() {
        assertThatThrownBy(() -> mapper.readValue("{\"taskId\":\"a\",\"name\":\"A\",\"type\":\"SHELL\",\"config\":{}}", TaskDefinition.class))
                .isInstanceOf(RuntimeException.class);
    }
    @Test void unknownConfigurationFieldIsRejectedEvenWithLenientApiMapper() {
        assertThatThrownBy(() -> mapper.readValue("{\"taskId\":\"a\",\"name\":\"A\",\"type\":\"DELAY\",\"config\":{\"durationMillis\":1,\"command\":\"whoami\"}}", TaskDefinition.class))
                .isInstanceOf(RuntimeException.class);
    }
}
