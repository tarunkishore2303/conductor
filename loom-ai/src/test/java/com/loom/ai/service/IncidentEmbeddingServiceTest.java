package com.loom.ai.service;

import com.loom.ai.config.AiEmbeddingProperties;
import com.loom.ai.model.IncidentEmbeddingModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IncidentEmbeddingServiceTest {
    private final IncidentEmbeddingModel model = mock(IncidentEmbeddingModel.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final IncidentEmbeddingService service = new IncidentEmbeddingService(model,
            new AiEmbeddingProperties("nomic-embed-text:v1.5", 768), metrics);

    @Test
    void embedsSanitizedTextAndMeasuresOperation() {
        float[] vector = new float[768];
        vector[0] = 1;
        when(model.embed("password=[REDACTED]")).thenReturn(vector);
        assertThat(service.embed("password=private-key")).containsExactly(vector).isNotSameAs(vector);
        verify(model).embed("password=[REDACTED]");
        assertThat(metrics.get("ai.requests").counter().count()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidDimensionsNonfiniteAndZeroVectors() {
        for (float[] vector : new float[][]{null, new float[]{1}, new float[768], invalid(Float.NaN), invalid(Float.POSITIVE_INFINITY)}) {
            when(model.embed(anyString())).thenReturn(vector);
            assertThatThrownBy(() -> service.embed("failure context")).isInstanceOf(AiOutputValidationException.class);
        }
        assertThat(metrics.get("ai.request.failures").counter().count()).isEqualTo(5);
    }

    @Test
    void directEmbeddingRequestRedactsQuotedJsonSecretKeys() {
        float[] vector = new float[768];
        vector[0] = 1;
        when(model.embed(anyString())).thenAnswer(invocation -> {
            assertThat((String) invocation.getArgument(0)).contains("[REDACTED]").doesNotContain("private-value");
            return vector;
        });
        assertThat(service.embed("{\"password\":\"private-value\"}")).hasSize(768);
    }

    @Test
    void rejectsUnboundedInputBeforeProvider() {
        assertThatThrownBy(() -> service.embed("x".repeat(4001))).isInstanceOf(AiInvalidRequestException.class);
        assertThatThrownBy(() -> service.embed(" ")).isInstanceOf(AiInvalidRequestException.class);
        verifyNoInteractions(model);
    }

    private float[] invalid(float value) {
        float[] vector = new float[768];
        vector[0] = value;
        return vector;
    }
}
