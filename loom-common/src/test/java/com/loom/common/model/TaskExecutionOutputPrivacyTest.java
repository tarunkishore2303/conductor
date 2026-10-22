package com.loom.common.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class TaskExecutionOutputPrivacyTest {
    @Test
    void ordinaryEntitySerializationOmitsCapturedOutputAndContentType() {
        var execution = new TaskExecution();
        execution.setStatus(TaskStatus.COMPLETE);
        execution.setOutputText("OUTPUT_PRIVATE_SENTINEL");
        execution.setOutputContentType("application/json");
        String json = JsonMapper.builder().build().writeValueAsString(execution);
        assertThat(json).contains("COMPLETE")
                .doesNotContain("OUTPUT_PRIVATE_SENTINEL", "outputText", "outputContentType", "application/json");
    }
}
