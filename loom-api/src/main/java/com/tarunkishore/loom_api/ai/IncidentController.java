package com.tarunkishore.loom_api.ai;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai")
public class IncidentController {
    private final IncidentIndexService index;
    private final SimilarIncidentService retrieval;
    private final FailureAnalysisService analyses;

    public IncidentController(
            IncidentIndexService index,
            SimilarIncidentService retrieval,
            FailureAnalysisService analyses) {
        this.index = index;
        this.retrieval = retrieval;
        this.analyses = analyses;
    }

    @GetMapping("/failure-analyses/{analysisId}")
    public FailureAnalysisService.Analysis analysis(@PathVariable UUID analysisId) {
        return analyses.getById(analysisId);
    }

    @PostMapping("/failure-analyses/{analysisId}/index")
    public IncidentIndexService.IndexResult index(@PathVariable UUID analysisId) {
        return index.index(analysisId);
    }

    @GetMapping("/runs/{jobId}/similar-incidents")
    public SimilarIncidentService.Retrieval retrieve(
            @PathVariable UUID jobId, @RequestParam(defaultValue = "3") int topK) {
        return retrieval.retrieve(jobId, topK);
    }

    @PostMapping("/runs/{jobId}/similar-incidents/synthesize")
    public SimilarIncidentService.SynthesisResponse synthesize(
            @PathVariable UUID jobId, @RequestParam(defaultValue = "3") int topK) {
        return retrieval.synthesize(jobId, topK);
    }
}
