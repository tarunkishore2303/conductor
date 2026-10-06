package com.tarunkishore.loom_api.repository;

import org.junit.jupiter.api.Test;
import com.tarunkishore.loom_api.ai.AiOutputException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class IncidentVectorRepositoryTest {
    @Test
    void validatesDimensionsFiniteCoordinatesAndNonzeroNormBeforeSql() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var repository = new IncidentVectorRepository(jdbc);
        assertThatThrownBy(() -> repository.search(UUID.randomUUID(), "test", new float[1], 3, 0.5))
                .isInstanceOf(AiOutputException.class);
        float[] vector = new float[768];
        assertThatThrownBy(() -> IncidentVectorRepository.validateVector(vector))
                .isInstanceOf(AiOutputException.class);
        vector[0] = Float.NaN;
        assertThatThrownBy(() -> IncidentVectorRepository.validateVector(vector))
                .isInstanceOf(AiOutputException.class);
        vector[0] = Float.POSITIVE_INFINITY;
        assertThatThrownBy(() -> IncidentVectorRepository.validateVector(vector))
                .isInstanceOf(AiOutputException.class);
        vector[0] = Float.MAX_VALUE;
        assertThatThrownBy(() -> IncidentVectorRepository.validateVector(vector))
                .isInstanceOf(AiOutputException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void vectorLiteralRoundTripPreservesCoordinates() {
        float[] vector = new float[768];
        vector[0] = 0.75f;
        vector[1] = -0.25f;
        assertThat(IncidentVectorRepository.parseVector(IncidentVectorRepository.vectorLiteral(vector)))
                .containsExactly(vector);
    }

    @Test
    void malformedStoredLiteralsAndInvalidSearchBoundsAreRejected() {
        assertThatThrownBy(() -> IncidentVectorRepository.parseVector("[malformed]"))
                .isInstanceOf(AiOutputException.class);
        assertThatThrownBy(() -> IncidentVectorRepository.parseVector("not-a-vector"))
                .isInstanceOf(AiOutputException.class);
        var repository = new IncidentVectorRepository(mock(JdbcTemplate.class));
        float[] vector = new float[768];
        vector[0] = 1;
        assertThatThrownBy(() -> repository.search(UUID.randomUUID(), "test", vector, 1000, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.search(UUID.randomUUID(), "test", vector, 3, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
