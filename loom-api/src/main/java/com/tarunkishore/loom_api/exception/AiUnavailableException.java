package com.tarunkishore.loom_api.exception;

public class AiUnavailableException extends RuntimeException {
    public AiUnavailableException() {
        super("AI generation is unavailable; try again later");
    }
}
