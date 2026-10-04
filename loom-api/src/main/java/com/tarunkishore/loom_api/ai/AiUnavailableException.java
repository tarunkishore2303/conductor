package com.tarunkishore.loom_api.ai;

public class AiUnavailableException extends RuntimeException {
    public AiUnavailableException() {
        super("AI generation is unavailable; try again later");
    }
}
