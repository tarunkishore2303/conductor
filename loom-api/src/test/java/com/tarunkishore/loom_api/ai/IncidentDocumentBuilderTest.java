package com.tarunkishore.loom_api.ai;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class IncidentDocumentBuilderTest {
    final IncidentDocumentBuilder builder = new IncidentDocumentBuilder();

    @Test
    void indexesFailedTasksOnlyWithBoundedRedactedEvidence() {
        var failed =
                new FailureContext.TaskEvidence(
                        UUID.randomUUID(),
                        "invoice",
                        "DEAD_LETTERED",
                        2,
                        List.of(
                                new FailureContext.Attempt(
                                        2,
                                        "FAILED",
                                        null,
                                        null,
                                        "Timeout",
                                        "password=secret-value " + "x".repeat(6000),
                                        null)),
                        null);
        var completed =
                new FailureContext.TaskEvidence(
                        UUID.randomUUID(), "done", "COMPLETE", 0, List.of(), null);
        var docs =
                builder.build(
                        new FailureContext(
                                UUID.randomUUID(),
                                "FAILED",
                                List.of(failed, completed),
                                List.of()));
        assertThat(docs).hasSize(1);
        assertThat(docs.getFirst().taskId()).isEqualTo(failed.taskId());
        assertThat(docs.getFirst().documentText())
                .hasSizeLessThanOrEqualTo(4000)
                .contains("DEAD_LETTERED", "Timeout", "Recorded attempts: 1")
                .doesNotContain("secret-value");
    }

    @Test
    void missingAndMalformedFactsRejected() {
        assertThatThrownBy(() -> builder.build(null)).isInstanceOf(AiOutputException.class);
        assertThatThrownBy(
                        () ->
                                builder.build(
                                        new FailureContext(
                                                UUID.randomUUID(), "FAILED", null, List.of())))
                .isInstanceOf(AiOutputException.class);
    }
}
