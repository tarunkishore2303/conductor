package com.loom.worker.exception;

/** Contains only an application-owned error code and safe message, never provider details. */
public final class TaskExecutionException extends Exception {
    private final String errorType;
    public TaskExecutionException(String errorType, String safeMessage) {
        super(safeMessage);
        this.errorType = errorType;
    }
    public String errorType() { return errorType; }
}
