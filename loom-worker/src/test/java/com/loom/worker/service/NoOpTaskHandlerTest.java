package com.loom.worker.service;

import com.loom.common.event.TaskEvent;
import org.junit.jupiter.api.Test;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class NoOpTaskHandlerTest {
    private TaskEvent event(String name) {
        return new TaskEvent(UUID.randomUUID(), UUID.randomUUID(), name, 0, 0);
    }

    @Test
    void demoFailuresAreDisabledByDefault() {
        assertThatCode(() -> new NoOpTaskHandler(false).execute(event("demoConnectionTimeout")))
                .doesNotThrowAnyException();
    }

    @Test
    void onlyFixedDemoNamesFailWhenEnabled() {
        var handler = new NoOpTaskHandler(true);
        assertThatThrownBy(() -> handler.execute(event("demoConnectionTimeout")))
                .isInstanceOf(ConnectException.class);
        assertThatThrownBy(() -> handler.execute(event("demoDownstreamTimeout")))
                .isInstanceOf(SocketTimeoutException.class);
        assertThatThrownBy(() -> handler.execute(event("demoInvalidJson")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> handler.execute(event("normal-task"))).doesNotThrowAnyException();
    }
}
