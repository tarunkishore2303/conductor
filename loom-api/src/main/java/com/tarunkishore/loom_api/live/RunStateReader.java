package com.tarunkishore.loom_api.live;

import com.loom.common.model.JobStatus;
import com.loom.common.model.TaskStatus;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A fresh read transaction observes commits from API, scheduler and workers alike. */
@Service
public class RunStateReader {
    private final NamedParameterJdbcTemplate jdbc;
    private final RunStreamProperties properties;

    public RunStateReader(DataSource source, RunStreamProperties properties) {
        var template = new JdbcTemplate(source);
        template.setQueryTimeout(5);
        this.jdbc = new NamedParameterJdbcTemplate(template);
        this.properties = properties;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Map<UUID, RunState> read(Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        if (ids.size() > properties.maxRuns()) throw capacity();
        var states = new LinkedHashMap<UUID, RunState>();
        // No entity hydration: output/config/error columns are deliberately not selected.
        jdbc.query("""
                SELECT j.id AS job_id,j.status AS job_status,j.updated_at AS job_updated,
                       t.id AS task_id,t.status AS task_status,t.retry_count,t.updated_at AS task_updated,
                       e.retry_scheduled_at,e.retry_recorded_at
                  FROM jobs j LEFT JOIN tasks t ON t.job_id=j.id
                  LEFT JOIN LATERAL (
                    SELECT max(retry_scheduled_at) AS retry_scheduled_at,
                           max(completed_at) FILTER (WHERE retry_scheduled_at IS NOT NULL) AS retry_recorded_at
                      FROM task_executions WHERE task_id=t.id
                  ) e ON true
                 WHERE j.id IN (:ids) ORDER BY j.id,t.id LIMIT :rowLimit
                """, Map.of("ids", ids, "rowLimit", properties.maxRuns() * properties.maxTasksPerRun() + 1), rs -> {
            UUID jobId = rs.getObject("job_id", UUID.class);
            var state = states.computeIfAbsent(jobId, ignored -> {
                try {
                    return new RunState(jobId, JobStatus.valueOf(rs.getString("job_status")),
                            instant(rs.getTimestamp("job_updated")), new LinkedHashMap<>());
                } catch (SQLException exception) {
                    throw new IllegalStateException("Run metadata could not be read", exception);
                }
            });
            UUID taskId = rs.getObject("task_id", UUID.class);
            if (taskId != null) {
                if (state.tasks().size() >= properties.maxTasksPerRun()) throw capacity();
                state.tasks().put(taskId, new TaskState(taskId, TaskStatus.valueOf(rs.getString("task_status")),
                        rs.getInt("retry_count"), instant(rs.getTimestamp("task_updated")),
                        instant(rs.getTimestamp("retry_scheduled_at")), instant(rs.getTimestamp("retry_recorded_at"))));
            }
        });
        states.replaceAll((id, state) -> new RunState(id, state.status(), state.updatedAt(), Map.copyOf(state.tasks())));
        return Map.copyOf(states);
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    static ResponseStatusException capacity() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Live run stream capacity exceeded");
    }

    public record RunState(UUID id, JobStatus status, Instant updatedAt, Map<UUID, TaskState> tasks) {
        public boolean terminal() {
            return status == JobStatus.COMPLETE || status == JobStatus.FAILED || status == JobStatus.CANCELLED;
        }
    }

    public record TaskState(UUID id, TaskStatus status, int retryCount, Instant updatedAt,
                            Instant retryScheduledAt, Instant retryRecordedAt) {}
}
