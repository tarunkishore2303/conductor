package com.loom.common.event;

import com.loom.common.outbox.OutboxMessages;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ExecutionIdentityTest {
    @Test void legacyIdsRemainStableAndReplayHasANewIdentity() {
        UUID task = UUID.randomUUID();
        assertThat(ExecutionIdentity.id(task, 0, 0)).isEqualTo(UUID.nameUUIDFromBytes((task + "-0").getBytes(StandardCharsets.UTF_8)));
        assertThat(ExecutionIdentity.id(task, 1, 0)).isEqualTo(ExecutionIdentity.id(task, 1, 0))
                .isNotEqualTo(ExecutionIdentity.id(task, 0, 0)).isNotEqualTo(ExecutionIdentity.id(task, 1, 1));
    }
    @Test void missingGenerationDefaultsToZero() {
        var event = new TaskEvent(UUID.randomUUID(), UUID.randomUUID(), "task", 2, 0);
        var mapper = JsonMapper.builder().build();
        String legacy = mapper.writeValueAsString(event).replace(",\"generation\":0", "");
        assertThat(mapper.readValue(legacy, TaskEvent.class).generation()).isZero();
    }
    @Test void queueDedupeIncludesGenerationAndAttempt() {
        UUID task = UUID.randomUUID(), job = UUID.randomUUID();
        assertThat(OutboxMessages.taskQueue(new TaskEvent(job, task, "task", 2, 0, 1), Instant.now()).dedupeKey())
                .isEqualTo("task-queue:" + task + ":1:0");
    }
    @Test void eventPayloadIsBoundedAndContainsOnlyTheContract() {
        var message = OutboxMessages.taskQueue(new TaskEvent(UUID.randomUUID(), UUID.randomUUID(), "task", 2, 0), Instant.now());
        assertThat(message.payloadJson()).doesNotContain("output", "config", "body");
        assertThatThrownBy(() -> OutboxMessages.taskQueue(new TaskEvent(UUID.randomUUID(), UUID.randomUUID(), "x".repeat(40000), 2, 0), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
