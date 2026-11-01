package com.loom.scheduler.listener;

import com.loom.common.event.JobCreatedEvent;
import com.loom.common.event.TaskResultEvent;
import com.loom.common.model.TaskStatus;
import com.loom.scheduler.service.DAGScheduler;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListenerCompletionTest {
    @Test void jobListenerCompletesDatabaseWorkBeforeReturning() {
        var scheduler = mock(DAGScheduler.class);
        UUID id = UUID.randomUUID();
        var committed = new AtomicBoolean();
        when(scheduler.scheduleJob(id)).thenReturn(Mono.fromRunnable(() -> committed.set(true)));
        new JobCreatedListener(scheduler).onJobCreated(new JobCreatedEvent(id));
        assertThat(committed).isTrue();
    }

    @Test void resultListenerPropagatesTransactionFailureForKafkaRedelivery() {
        var scheduler = mock(DAGScheduler.class);
        var event = new TaskResultEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TaskStatus.COMPLETE, null);
        when(scheduler.onTaskResult(event)).thenReturn(Mono.error(new IllegalStateException("transaction rolled back")));
        assertThatThrownBy(() -> new TaskResultListener(scheduler).onTaskResult(event)).isInstanceOf(IllegalStateException.class);
    }
}
