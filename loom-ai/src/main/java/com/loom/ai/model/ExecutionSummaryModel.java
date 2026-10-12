package com.loom.ai.model;

public interface ExecutionSummaryModel {
    ExecutionSummaryInterpretation summarize(ExecutionSummaryRequest request);
}
