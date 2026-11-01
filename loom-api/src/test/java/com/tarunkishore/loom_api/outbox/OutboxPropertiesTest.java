package com.tarunkishore.loom_api.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class OutboxPropertiesTest {
    @Test void defaultPropertiesBind() {
        var p = new Binder(new MapConfigurationPropertySource(Map.of("conductor.outbox.enabled", "true")))
                .bind("conductor.outbox", OutboxProperties.class).get();
        assertThat(p.batchSize()).isEqualTo(50);
        assertThat(p.claimTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.publishTimeout()).isEqualTo(Duration.ofSeconds(10));
    }
    @Test void invalidBoundsFailAtStartup() {
        assertThatThrownBy(() -> new OutboxProperties(true, Duration.ZERO, 50, Duration.ofSeconds(30), Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(true, Duration.ofMillis(500), 201, Duration.ofSeconds(30), Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(true, Duration.ofMillis(500), 50, Duration.ofSeconds(20), Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
