package com.loom.common.dto;

import static org.assertj.core.api.Assertions.*;
import com.loom.capability.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class TaskDefinitionCompatibilityTest {
    private final JsonMapper mapper = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    @Test void historicalJsonAndOldConstructorRemainNoop() {
        TaskDefinition legacy = mapper.readValue("{\"taskId\":\"a\",\"name\":\"A\",\"dependsOn\":[],\"maxRetries\":2}", TaskDefinition.class);
        assertThat(legacy).isEqualTo(new TaskDefinition("a", "A", List.of(), 2));
        assertThat(legacy.type()).isEqualTo(TaskType.NOOP);
        assertThat(legacy.config()).isEqualTo(new NoOpTaskConfig());
    }
    @Test void typedConfigRoundTripsWithReadableSiblingType() {
        TaskDefinition task = new TaskDefinition("wait", "Wait", List.of("a"), 2, TaskType.DELAY, new DelayTaskConfig(10));
        String json = mapper.writeValueAsString(task);
        assertThat(mapper.readValue(json, TaskDefinition.class)).isEqualTo(task);
        assertThat(json).contains("\"type\":\"DELAY\"", "\"config\":{\"durationMillis\":10}");
    }
    @Test void unknownConfigurationFieldsNeverIgnored() {
        assertThatThrownBy(() -> mapper.readValue("{\"taskId\":\"a\",\"name\":\"A\",\"maxRetries\":0,\"type\":\"DELAY\",\"config\":{\"durationMillis\":10,\"script\":\"x\"}}", TaskDefinition.class))
                .isInstanceOf(RuntimeException.class);
    }
    @Test void missingNonNoopConfigRemainsInvalidRatherThanDefaulting() {
        var task = mapper.readValue("{\"taskId\":\"a\",\"name\":\"A\",\"maxRetries\":0,\"type\":\"DELAY\"}", TaskDefinition.class);
        assertThat(task.config()).isNull();
    }
}
