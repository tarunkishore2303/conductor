package com.loom.ai.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class EvidenceRedactorTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"password\":\"private-value\",\"status\":\"FAILED\"}",
            "{'api_key':'private-value','status':'FAILED'}",
            "\"Token\" : \"private-value\"",
            "password=private-value",
            "https://example.com?token=private-value&status=FAILED",
            "{\"secret\":\"private-value with \\\"quotes\\\"\"}"})
    void redactsQuotedAndUnquotedSecrets(String evidence) {
        assertThat(EvidenceRedactor.redact(evidence)).contains("[REDACTED]").doesNotContain("private-value");
    }

    @Test
    void queryRedactionPreservesOtherParameters() {
        assertThat(EvidenceRedactor.redact("https://example.com?token=private-value&status=FAILED"))
                .isEqualTo("https://example.com?token=[REDACTED]&status=FAILED");
    }
}
