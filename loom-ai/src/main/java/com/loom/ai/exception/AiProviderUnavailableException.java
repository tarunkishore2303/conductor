package com.loom.ai.exception;

public class AiProviderUnavailableException extends RuntimeException {
    public AiProviderUnavailableException() { super("AI provider is unavailable. Try again later."); }
}
