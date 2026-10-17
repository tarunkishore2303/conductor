package com.loom.capability;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;
public record DelayTaskConfig(@JsonProperty(required = true) @JsonDeserialize(using = StrictTaskNumbers.MillisLong.class)
        long durationMillis) implements TaskConfig { }
