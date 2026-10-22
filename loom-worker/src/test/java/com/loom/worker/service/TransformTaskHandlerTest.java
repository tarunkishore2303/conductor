package com.loom.worker.service;

import com.loom.worker.exception.TaskExecutionException;

import com.loom.capability.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransformTaskHandlerTest {
    private final TaskCapabilityValidators validators = mock(TaskCapabilityValidators.class);
    private final TransformTaskHandler handler = new TransformTaskHandler(validators, TaskProperties.defaults());
    @Test void selectsTopLevelFields() throws Exception {
        var result = run(new TransformTaskConfig("JSON_SELECT", "{\"id\":42,\"email\":\"x@example.com\",\"internal\":\"hidden\"}", List.of("id", "email"), null));
        assertThat(result.output()).isEqualTo("{\"id\":42,\"email\":\"x@example.com\"}");
    }
    @Test void missingSelectedFieldFails() {
        assertThatThrownBy(() -> run(new TransformTaskConfig("JSON_SELECT", "{}", List.of("missing"), null))).isInstanceOf(TaskExecutionException.class);
    }
    @Test void renamesTopLevelFields() throws Exception {
        assertThat(run(new TransformTaskConfig("JSON_RENAME", "{\"customer_id\":42}", null, Map.of("customer_id", "customerId"))).output()).isEqualTo("{\"customerId\":42}");
    }
    @Test void missingRenameSourceFails() {
        assertThatThrownBy(() -> run(new TransformTaskConfig("JSON_RENAME", "{}", null, Map.of("id", "other")))).isInstanceOf(TaskExecutionException.class);
    }
    @Test void renameCollisionFails() {
        assertThatThrownBy(() -> run(new TransformTaskConfig("JSON_RENAME", "{\"id\":1,\"other\":2}", null, Map.of("id", "other")))).isInstanceOf(TaskExecutionException.class);
    }
    @Test void malformedJsonFailsSafely() {
        assertThatThrownBy(() -> run(new TransformTaskConfig("JSON_SELECT", "broken password=hidden", List.of("id"), null)))
                .isInstanceOf(TaskExecutionException.class).hasMessageNotContaining("password");
    }
    @Test void nonObjectFails() {
        assertThatThrownBy(() -> run(new TransformTaskConfig("JSON_SELECT", "[]", List.of("id"), null))).isInstanceOf(TaskExecutionException.class);
    }
    @Test void expressionIsNeverExecuted() {
        assertThatThrownBy(() -> run(new TransformTaskConfig("SpEL", "{}", null, null)))
                .isInstanceOf(TaskExecutionException.class).hasMessage("Transform operation is unsupported");
    }
    @Test void validatorRejectionIsSanitized() {
        doThrow(new IllegalArgumentException("secret input")).when(validators).validate(any(), any());
        assertThatThrownBy(() -> run(new TransformTaskConfig("JSON_SELECT", "{}", List.of("id"), null)))
                .isInstanceOf(TaskExecutionException.class).hasMessageNotContaining("secret");
    }
    @Test void dependencyJsonIsSelectedWithoutChangingConfig() throws Exception {
        var config = new TransformTaskConfig("JSON_SELECT", null, List.of("id"), null, "fetch");
        var result = handler.execute(new TaskExecutionContext(null, config,
                new ResolvedTaskInput("fetch", "{\"id\":42,\"private\":true}", "application/json")));
        assertThat(result.output()).isEqualTo("{\"id\":42}");
        assertThat(result.contentType()).isEqualTo("application/json");
        assertThat(config.input()).isNull();
        assertThat(config.inputTaskId()).isEqualTo("fetch");
    }
    @Test void referencedInputMustExistAndBeJson() {
        var config = new TransformTaskConfig("JSON_SELECT", null, List.of("id"), null, "fetch");
        assertThatThrownBy(() -> handler.execute(new TaskExecutionContext(null, config)))
                .isInstanceOfSatisfying(TaskExecutionException.class, failure -> assertThat(failure.errorType()).isEqualTo("TASK_OUTPUT_MISSING"));
        assertThatThrownBy(() -> handler.execute(new TaskExecutionContext(null, config,
                new ResolvedTaskInput("fetch", "{}", "text/plain"))))
                .isInstanceOfSatisfying(TaskExecutionException.class, failure -> assertThat(failure.errorType()).isEqualTo("INCOMPATIBLE_TASK_OUTPUT"));
    }
    @Test void referencedMalformedAndOversizedInputsFailSafely() {
        var config = new TransformTaskConfig("JSON_SELECT", null, List.of("id"), null, "fetch");
        for (String input : List.of("secret malformed", "{} {}", "x".repeat(16385))) {
            assertThatThrownBy(() -> handler.execute(new TaskExecutionContext(null, config,
                    new ResolvedTaskInput("fetch", input, "application/json"))))
                    .isInstanceOf(TaskExecutionException.class).hasMessageNotContaining("secret");
        }
    }
    @Test void configuredOutputLimitAppliesToTransforms() {
        var defaults = TaskProperties.defaults();
        var limited = new TransformTaskHandler(validators,
                new TaskProperties(defaults.delay(), defaults.http(), defaults.transform(), new TaskProperties.Output(4)));
        assertThatThrownBy(() -> limited.execute(new TaskExecutionContext(null,
                new TransformTaskConfig("JSON_SELECT", "{\"id\":42}", List.of("id"), null))))
                .isInstanceOfSatisfying(TaskExecutionException.class, failure -> assertThat(failure.errorType()).isEqualTo("TASK_OUTPUT_TOO_LARGE"));
    }
    private TaskExecutionResult run(TransformTaskConfig config) throws Exception { return handler.execute(new TaskExecutionContext(null, config)); }
}
