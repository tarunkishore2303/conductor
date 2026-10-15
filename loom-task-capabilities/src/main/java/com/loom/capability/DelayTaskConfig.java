package com.loom.capability;
import com.fasterxml.jackson.annotation.JsonProperty;
public record DelayTaskConfig(@JsonProperty(required = true) long durationMillis) implements TaskConfig { }
