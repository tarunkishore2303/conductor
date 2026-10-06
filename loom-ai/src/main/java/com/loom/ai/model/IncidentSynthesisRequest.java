package com.loom.ai.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record IncidentSynthesisRequest(
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String currentAnalysisId,
        @NotBlank @Size(max = 4000) String currentIncident,
        @NotNull @Size(max = 5) List<@NotNull @Valid IncidentMatch> matches) {}
