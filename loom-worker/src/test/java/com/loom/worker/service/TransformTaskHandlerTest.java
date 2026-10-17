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
    private final TransformTaskHandler handler = new TransformTaskHandler(validators);
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
    private TaskExecutionResult run(TransformTaskConfig config) throws Exception { return handler.execute(new TaskExecutionContext(null, config)); }
}
