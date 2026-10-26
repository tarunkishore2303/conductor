package com.tarunkishore.loom_api.live;

import com.tarunkishore.loom_api.live.RunEvent.Type;
import com.tarunkishore.loom_api.live.RunStateReader.RunState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Maps observed committed transitions to hints; deliberately neither schedules nor writes. */
@Component
public class RunEventPublisher {
    public List<RunEvent> changes(RunState previous, RunState current) {
        var events = new ArrayList<RunEvent>();
        current.tasks().values().forEach(task -> {
            var old = previous.tasks().get(task.id());
            if (old == null) return;
            if (task.retryScheduledAt() != null && !Objects.equals(old.retryScheduledAt(), task.retryScheduledAt())) {
                events.add(new RunEvent(UUID.randomUUID(), current.id(), task.id(), Type.TASK_RETRY_SCHEDULED,
                        task.retryRecordedAt(), task.status(), current.status(), task.retryCount()));
            }
            if (old.status() != task.status()) {
                Type type = switch (task.status()) {
                    case RUNNING -> Type.TASK_STARTED;
                    case COMPLETE -> Type.TASK_COMPLETED;
                    case FAILED -> Type.TASK_FAILED;
                    case DEAD_LETTERED -> Type.TASK_DEAD_LETTERED;
                    case CANCELLED -> Type.TASK_CANCELLED;
                    case PENDING -> null;
                };
                if (type != null) events.add(new RunEvent(UUID.randomUUID(), current.id(), task.id(), type,
                        task.updatedAt(), task.status(), current.status(), task.retryCount()));
            }
        });
        if (previous.status() != current.status()) {
            RunEvent event = jobEvent(current);
            if (event != null) events.add(event);
        }
        return List.copyOf(events);
    }

    public RunEvent jobEvent(RunState state) {
        Type type = switch (state.status()) {
            case RUNNING -> Type.JOB_STARTED;
            case COMPLETE -> Type.JOB_COMPLETED;
            case FAILED -> Type.JOB_FAILED;
            case CANCELLING -> Type.JOB_CANCELLING;
            case CANCELLED -> Type.JOB_CANCELLED;
            case CREATED -> null;
        };
        return type == null ? null : new RunEvent(UUID.randomUUID(), state.id(), null, type,
                state.updatedAt(), null, state.status(), null);
    }
}
