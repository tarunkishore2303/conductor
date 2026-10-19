package com.tarunkishore.loom_api.ai.optimization;

import com.tarunkishore.loom_api.ai.optimization.config.OptimizationProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class OptimizationPropertiesTest {
    @Test void safeDefaults() { assertThat(OptimizationProperties.defaults().maxRuns()).isEqualTo(50); }
    @Test void invalidBoundsRejected() {
        assertThatThrownBy(() -> new OptimizationProperties(51,5,20,Duration.ofDays(30),1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OptimizationProperties(50,1,20,Duration.ofDays(30),1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OptimizationProperties(50,5,21,Duration.ofDays(30),1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OptimizationProperties(50,5,20,Duration.ZERO,1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OptimizationProperties(50,5,20,Duration.ofDays(30),1001)).isInstanceOf(IllegalArgumentException.class);
    }
}
