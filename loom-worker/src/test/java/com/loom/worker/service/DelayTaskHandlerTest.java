package com.loom.worker.service;

import com.loom.worker.exception.TaskExecutionException;

import com.loom.capability.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class DelayTaskHandlerTest {
    private final TaskProperties defaults = TaskProperties.defaults();
    private final DelayTaskHandler handler = new DelayTaskHandler(new TaskProperties(new TaskProperties.Delay(Duration.ofMillis(2)), defaults.http(), defaults.transform()));
    @Test void acceptsZero() throws Exception { handler.execute(context(0)); }
    @Test void acceptsSmallDelay() throws Exception { handler.execute(context(1)); }
    @Test void acceptsMaximum() throws Exception { handler.execute(context(2)); }
    @Test void rejectsNegative() { assertThatThrownBy(() -> handler.execute(context(-1))).isInstanceOf(TaskExecutionException.class); }
    @Test void rejectsAboveMaximum() { assertThatThrownBy(() -> handler.execute(context(3))).isInstanceOf(TaskExecutionException.class); }
    @Test void honorsInterruption() {
        Thread.currentThread().interrupt();
        try { assertThatThrownBy(() -> handler.execute(context(1))).isInstanceOf(InterruptedException.class); }
        finally { Thread.interrupted(); }
    }
    private TaskExecutionContext context(long duration) { return new TaskExecutionContext(null, new DelayTaskConfig(duration)); }
}
