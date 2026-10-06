package com.loom.ai.model;

public interface FailureInterpretationModel {
    FailureInterpretation analyze(FailureFactsRequest facts);
}
