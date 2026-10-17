package com.loom.worker.service;

import com.loom.worker.exception.TaskExecutionException;

import com.loom.capability.TaskType;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskHandlerRegistryTest {
    @Test void discoversEveryRegisteredType() throws Exception {
        var handlers = Arrays.stream(TaskType.values()).map(type -> {
            var handler = mock(TaskHandler.class); when(handler.type()).thenReturn(type); return handler;
        }).toList();
        var registry = new TaskHandlerRegistry(handlers);
        for (var handler : handlers) assertThat(registry.requireHandler(handler.type())).isSameAs(handler);
    }
    @Test void rejectsDuplicateRegistration() {
        var handler = mock(TaskHandler.class); when(handler.type()).thenReturn(TaskType.NOOP);
        assertThatThrownBy(() -> new TaskHandlerRegistry(List.of(handler, handler))).isInstanceOf(IllegalStateException.class).hasMessageContaining("NOOP");
    }
    @Test void rejectsUnregisteredType() {
        assertThatThrownBy(() -> new TaskHandlerRegistry(List.of()).requireHandler(TaskType.HTTP_REQUEST))
                .isInstanceOf(TaskExecutionException.class).hasMessageContaining("no registered handler");
    }
    @Test void rejectsNullType() {
        assertThatThrownBy(() -> new TaskHandlerRegistry(List.of()).requireHandler(null)).isInstanceOf(TaskExecutionException.class);
    }
}
