package com.tarunkishore.loom_api.ai.summary;

import java.util.List;

public interface ExecutionSummaryClient {
    Result summarize(ExecutionSummaryFacts facts);

    record Interpretation(
            String overview, List<String> notableEvents, List<String> operationalNotes) {}

    record Result(Interpretation interpretation, String model) {}
}
