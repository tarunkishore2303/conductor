package com.tarunkishore.loom_api.live;

import com.tarunkishore.loom_api.live.RunStateReader.RunState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-local, bounded live convenience channel. There is no replay/event history. */
@Component
public class RunEventStream {
    private final RunStateReader reader;
    private final RunEventPublisher publisher;
    private final RunStreamProperties properties;
    private final Map<UUID, Subscription> clients = new HashMap<>();
    private final Map<UUID, RunState> observed = new HashMap<>();
    private final ScheduledExecutorService observer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("run-stream-observer").factory());
    private final ThreadPoolExecutor deliveryPool = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100), Thread.ofPlatform().daemon().name("run-stream-delivery-", 0).factory());
    private final Executor delivery;
    private final Clock clock;

    @Autowired
    public RunEventStream(RunStateReader reader, RunEventPublisher publisher, RunStreamProperties properties) {
        this(reader, publisher, properties, null, Clock.systemUTC());
    }

    RunEventStream(RunStateReader reader, RunEventPublisher publisher, RunStreamProperties properties,
                   Executor delivery, Clock clock) {
        this.reader = reader;
        this.publisher = publisher;
        this.properties = properties;
        this.delivery = delivery == null ? deliveryPool : delivery;
        this.clock = clock;
    }

    @PostConstruct void start() {
        observer.scheduleWithFixedDelay(this::observe, properties.observationIntervalMillis(),
                properties.observationIntervalMillis(), TimeUnit.MILLISECONDS);
    }

    public SseEmitter subscribe(UUID jobId) {
        // Read before allocating any connection; unknown jobs reliably return 404.
        var state = reader.read(List.of(jobId)).get(jobId);
        if (state == null) throw new NoSuchElementException("Run not found");
        var emitter = newEmitter();
        var subscription = new Subscription(UUID.randomUUID(), jobId, emitter, clock.millis());
        synchronized (this) {
            if (clients.size() >= properties.maxConnections()
                    || (!observed.containsKey(jobId) && observed.size() >= properties.maxRuns())) {
                throw RunStateReader.capacity();
            }
            clients.put(subscription.id, subscription);
            observed.putIfAbsent(jobId, state);
        }
        emitter.onCompletion(() -> remove(subscription));
        emitter.onTimeout(() -> close(subscription));
        emitter.onError(error -> remove(subscription));
        dispatch(subscription, state.terminal() ? List.of(publisher.jobEvent(state)) : List.of(), true, state.terminal());
        return emitter;
    }

    SseEmitter newEmitter() { return new SseEmitter(properties.timeoutMillis()); }

    void observe() {
        Map<UUID, RunState> previous;
        synchronized (this) { previous = Map.copyOf(observed); }
        if (previous.isEmpty()) return;
        try {
            var current = reader.read(previous.keySet());
            for (var entry : previous.entrySet()) {
                var state = current.get(entry.getKey());
                List<Subscription> listeners;
                synchronized (this) {
                    listeners = clients.values().stream().filter(client -> client.jobId.equals(entry.getKey())).toList();
                    if (observed.containsKey(entry.getKey()) && state != null) observed.put(entry.getKey(), state);
                }
                if (state == null) { listeners.forEach(this::close); continue; }
                var events = publisher.changes(entry.getValue(), state);
                for (var listener : listeners) {
                    if (clock.millis() - listener.createdAt >= properties.timeoutMillis()) close(listener);
                    else dispatch(listener, events, false, state.terminal());
                }
            }
        } catch (Exception failure) {
            // A healthy-looking stream must not silently mask a broken DB observer.
            List<Subscription> listeners;
            synchronized (this) { listeners = new ArrayList<>(clients.values()); }
            listeners.forEach(this::close);
        }
    }

    private void dispatch(Subscription client, List<RunEvent> events, boolean ready, boolean terminal) {
        boolean heartbeat = clock.millis() - client.lastSent >= properties.heartbeatMillis();
        if (events.isEmpty() && !ready && !terminal && !heartbeat) return;
        // At most one queued/running send per client. A slow client reconnects and
        // recovers through REST instead of keeping a deceptively healthy stale stream.
        if (!client.sending.compareAndSet(false, true)) {
            if (!events.isEmpty() || ready || terminal) close(client);
            return;
        }
        try {
            delivery.execute(() -> {
                try {
                    synchronized (RunEventStream.this) { if (!clients.containsKey(client.id)) return; }
                    if (ready) client.emitter.send(SseEmitter.event().name("ready").data(client.jobId).reconnectTime(2000));
                    for (var event : events) {
                        client.emitter.send(SseEmitter.event().id(event.eventId().toString()).name(event.type().name()).data(event));
                    }
                    if (heartbeat && events.isEmpty() && !ready) client.emitter.send(SseEmitter.event().comment("heartbeat"));
                    client.lastSent = clock.millis();
                    if (terminal) close(client);
                } catch (IOException disconnected) {
                    // Servlet container owns async error completion after a failed write.
                    remove(client);
                } catch (RuntimeException disconnected) {
                    close(client);
                } finally {
                    client.sending.set(false);
                }
            });
        } catch (RuntimeException overloaded) {
            client.sending.set(false);
            close(client);
        }
    }

    private void close(Subscription client) {
        remove(client);
        try { client.emitter.complete(); } catch (RuntimeException ignored) { /* Already disconnected. */ }
    }

    private synchronized void remove(Subscription client) {
        clients.remove(client.id);
        if (clients.values().stream().noneMatch(other -> other.jobId.equals(client.jobId))) observed.remove(client.jobId);
    }

    synchronized int connectionCount() { return clients.size(); }

    @PreDestroy void stop() {
        observer.shutdownNow();
        List<Subscription> listeners;
        synchronized (this) { listeners = new ArrayList<>(clients.values()); }
        listeners.forEach(this::close);
        deliveryPool.shutdownNow();
    }

    private static final class Subscription {
        final UUID id;
        final UUID jobId;
        final SseEmitter emitter;
        final long createdAt;
        final AtomicBoolean sending = new AtomicBoolean();
        volatile long lastSent;

        Subscription(UUID id, UUID jobId, SseEmitter emitter, long now) {
            this.id = id;
            this.jobId = jobId;
            this.emitter = emitter;
            this.createdAt = now;
            this.lastSent = now;
        }
    }
}
