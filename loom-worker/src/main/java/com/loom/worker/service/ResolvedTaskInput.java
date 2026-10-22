package com.loom.worker.service;

/** Whole successful output from one declared direct dependency; never persisted as configuration. */
public record ResolvedTaskInput(String sourceTaskId, String output, String contentType) {}
