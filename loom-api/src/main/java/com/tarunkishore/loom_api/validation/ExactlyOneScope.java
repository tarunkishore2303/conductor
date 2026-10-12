package com.tarunkishore.loom_api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ExactlyOneScopeValidator.class)
public @interface ExactlyOneScope {
    String message() default "Supply exactly one jobId, workflowId or analysisId";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
