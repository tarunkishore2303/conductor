package com.loom.ai.service;

public class AiProviderUnavailableException extends RuntimeException {
    public AiProviderUnavailableException() { super("AI provider is unavailable. Try again later."); }
}
