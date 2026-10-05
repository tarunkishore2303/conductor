package com.loom.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FailureEvidenceSanitizerTest {
    @Test
    void retainsUsefulFailureFactsWhileRedactingCredentials() {
        String error = "HTTP 503 password=hunter2 token=abc api_key=xyz Bearer bearerSecret "
                + "https://user:pass@example.test/orders";
        String result = FailureEvidenceSanitizer.sanitize(error);
        assertThat(result).contains("HTTP 503", "example.test/orders", "[REDACTED]")
                .doesNotContain("hunter2", "abc", "xyz", "bearerSecret", "user:pass");
    }

    @Test
    void redactsQuotedJsonAndQueryCredentials() {
        String result = FailureEvidenceSanitizer.sanitize(
                "{\"password\":\"sensitive value\"} /orders?token=private&limit=10");
        assertThat(result).doesNotContain("sensitive", "private").contains("limit=10");
    }

    @Test
    void redactsBasicCredentialsBeforeAuthorizationKeyReplacement() {
        String result = FailureEvidenceSanitizer.sanitize(
                "HTTP 503 Authorization: Basic ZmFrZTpmYWtl Basic c2Vjb25kOmZha2U=");
        assertThat(result).contains("HTTP 503", "[REDACTED]")
                .doesNotContain("ZmFrZTpmYWtl", "c2Vjb25kOmZha2U=");
        assertThat(FailureEvidenceSanitizer.sanitize(result)).isEqualTo(result);
    }

    @Test
    void boundsOutputAndRemovesUnsafeControls() {
        assertThat(FailureEvidenceSanitizer.sanitize("x".repeat(10000))).hasSize(2048);
        assertThat(FailureEvidenceSanitizer.sanitize("error\u0000bad")).isEqualTo("errorbad");
        assertThat(FailureEvidenceSanitizer.sanitize(null)).isNull();
    }
}
