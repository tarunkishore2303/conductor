package com.loom.capability;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonAnySetter;

/** JSON has a sibling task type; configuration shape is independently checked against it. */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, defaultImpl = NoOpTaskConfig.class)
@JsonSubTypes({@JsonSubTypes.Type(NoOpTaskConfig.class), @JsonSubTypes.Type(DelayTaskConfig.class),
        @JsonSubTypes.Type(HttpRequestTaskConfig.class), @JsonSubTypes.Type(TransformTaskConfig.class)})
public sealed interface TaskConfig permits NoOpTaskConfig, DelayTaskConfig, HttpRequestTaskConfig, TransformTaskConfig {
    @JsonAnySetter
    default void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown task configuration field");
    }
}
