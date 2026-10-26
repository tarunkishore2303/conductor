package com.tarunkishore.loom_api.live;

import com.loom.common.model.JobStatus;
import com.tarunkishore.loom_api.live.RunStateReader.RunState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RunEventStreamTest {
    private final RunStateReader reader = mock(RunStateReader.class);
    private final UUID job = UUID.randomUUID();
    private final MutableClock clock = new MutableClock();
    private final List<RecordingEmitter> emitters = new ArrayList<>();
    private final RunEventStream stream = new RunEventStream(reader, new RunEventPublisher(), properties(2), Runnable::run, clock) {
        @Override SseEmitter newEmitter() {
            var emitter = new RecordingEmitter();
            emitters.add(emitter);
            return emitter;
        }
    };

    static RunStreamProperties properties(int connections) { return new RunStreamProperties(connections, 2, 100, 1000, 15000, 300000); }
    private RunState state(JobStatus status) { return new RunState(job, status, Instant.EPOCH, Map.of()); }
    private void returns(JobStatus status) { when(reader.read(any())).thenReturn(Map.of(job, state(status))); }

    @AfterEach void cleanup() { stream.stop(); }

    @Test void existingRunConnectsAndUnknownRunDoesNotAllocateConnection() {
        when(reader.read(any())).thenReturn(Map.of());
        assertThatThrownBy(() -> stream.subscribe(job)).isInstanceOf(NoSuchElementException.class);
        assertThat(stream.connectionCount()).isZero();
        returns(JobStatus.RUNNING);
        stream.subscribe(job);
        assertThat(stream.connectionCount()).isOne();
        assertThat(emitters.getFirst().sent.toString()).contains("ready");
    }

    @Test void completionClosesEverySubscriber() {
        returns(JobStatus.RUNNING);
        stream.subscribe(job); stream.subscribe(job);
        returns(JobStatus.COMPLETE);
        stream.observe();
        assertThat(stream.connectionCount()).isZero();
        assertThat(emitters).allSatisfy(emitter -> {
            assertThat(emitter.complete).isTrue();
            assertThat(emitter.events()).extracting(RunEvent::type).containsExactly(RunEvent.Type.JOB_COMPLETED);
        });
    }

    @Test void terminalSubscriptionSendsFinalHintAndDoesNotRemainRegistered() {
        returns(JobStatus.FAILED);
        stream.subscribe(job);
        assertThat(stream.connectionCount()).isZero();
        assertThat(emitters.getFirst().events()).extracting(RunEvent::type).containsExactly(RunEvent.Type.JOB_FAILED);
    }

    @Test void failedSubscriberDoesNotBreakOtherClients() {
        returns(JobStatus.RUNNING);
        stream.subscribe(job); stream.subscribe(job);
        emitters.getFirst().fail = true;
        returns(JobStatus.CANCELLING);
        stream.observe();
        assertThat(stream.connectionCount()).isOne();
        assertThat(emitters.get(1).events()).extracting(RunEvent::type).containsExactly(RunEvent.Type.JOB_CANCELLING);
        assertThat(emitters.getFirst().complete).isFalse(); // container completes failed writes
    }

    @Test void completionTimeoutAndErrorCallbacksRemoveConnections() {
        returns(JobStatus.RUNNING);
        stream.subscribe(job); emitters.getLast().completion.run();
        assertThat(stream.connectionCount()).isZero();
        stream.subscribe(job); emitters.getLast().timeout.run();
        assertThat(stream.connectionCount()).isZero();
        stream.subscribe(job); emitters.getLast().error.accept(new IOException());
        assertThat(stream.connectionCount()).isZero();
    }

    @Test void heartbeatIsACommentAndIdleConnectionLifetimeIsBoundedWithoutSleeps() {
        returns(JobStatus.RUNNING);
        stream.subscribe(job);
        clock.millis = 15000;
        stream.observe();
        assertThat(emitters.getFirst().sent.toString()).contains(":heartbeat");
        assertThat(emitters.getFirst().events()).isEmpty();
        clock.millis = 300000;
        stream.observe();
        assertThat(stream.connectionCount()).isZero();
    }

    @Test void connectionCapacityIsEnforced() {
        returns(JobStatus.RUNNING);
        stream.subscribe(job); stream.subscribe(job);
        assertThatThrownBy(() -> stream.subscribe(job)).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(503));
        assertThat(stream.connectionCount()).isEqualTo(2);
    }

    @Test void databaseFailureClosesStreamsInsteadOfKeepingHealthyLookingStaleConnections() {
        returns(JobStatus.RUNNING);
        stream.subscribe(job);
        when(reader.read(any())).thenThrow(new IllegalStateException("database unavailable"));
        stream.observe();
        assertThat(stream.connectionCount()).isZero();
        assertThat(emitters.getFirst().complete).isTrue();
    }

    @Test void disconnectedRunsAreNotQueriedAgain() {
        returns(JobStatus.RUNNING);
        stream.subscribe(job); emitters.getFirst().completion.run();
        clearInvocations(reader);
        stream.observe();
        verifyNoInteractions(reader);
    }

    static class RecordingEmitter extends SseEmitter {
        final List<Object> sent = new ArrayList<>();
        Runnable completion = () -> {}, timeout = () -> {};
        Consumer<Throwable> error = ignored -> {};
        boolean fail, complete;
        @Override public void send(SseEventBuilder builder) throws IOException {
            if (fail) throw new IOException("closed");
            builder.build().forEach(part -> sent.add(part.getData()));
        }
        @Override public void onCompletion(Runnable callback) { completion = callback; }
        @Override public void onTimeout(Runnable callback) { timeout = callback; }
        @Override public void onError(Consumer<Throwable> callback) { error = callback; }
        @Override public void complete() { complete = true; completion.run(); }
        List<RunEvent> events() { return sent.stream().filter(RunEvent.class::isInstance).map(RunEvent.class::cast).toList(); }
    }

    static class MutableClock extends Clock {
        long millis;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }
}
