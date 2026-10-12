package com.tarunkishore.loom_api.validation;

import com.tarunkishore.loom_api.ai.copilot.CopilotService;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Structural request validation; database ownership and run state checks stay in services. */
public final class ExactlyOneScopeValidator implements ConstraintValidator<ExactlyOneScope, CopilotService.Query> {
    @Override public boolean isValid(CopilotService.Query value, ConstraintValidatorContext context) {
        if (value == null) return true;
        return (value.jobId() == null ? 0 : 1) + (value.workflowId() == null ? 0 : 1)
                + (value.analysisId() == null ? 0 : 1) == 1;
    }
}
