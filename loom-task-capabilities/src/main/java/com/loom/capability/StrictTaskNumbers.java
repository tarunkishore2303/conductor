package com.loom.capability;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Keep capability time bounds exact even when a host application's mapper permits scalar coercion. */
public final class StrictTaskNumbers {
    private StrictTaskNumbers() { }

    public static final class MillisLong extends ValueDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
                return context.reportInputMismatch(Long.class, "Task duration must be a JSON integer");
            return parser.getLongValue();
        }
        @Override public Long getNullValue(DeserializationContext context) {
            return context.reportInputMismatch(Long.class, "Task duration is required");
        }
    }

    public static final class MillisInteger extends ValueDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
                return context.reportInputMismatch(Integer.class, "HTTP timeout must be a JSON integer");
            return parser.getIntValue();
        }
        @Override public Integer getNullValue(DeserializationContext context) {
            return context.reportInputMismatch(Integer.class, "HTTP timeout is required");
        }
    }
}
