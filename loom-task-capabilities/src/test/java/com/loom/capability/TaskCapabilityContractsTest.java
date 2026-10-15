package com.loom.capability;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class TaskCapabilityContractsTest {
    private final TaskProperties properties = TaskProperties.defaults();
    private final TaskCapabilityValidators validators = new TaskCapabilityValidators(List.of(new NoOpTaskConfigValidator(),
            new DelayTaskConfigValidator(properties), new HttpRequestTaskConfigValidator(properties), new TransformTaskConfigValidator(properties)));
    private final TaskConfigCodec codec = new TaskConfigCodec(validators);

    @Test void allFourConfigTypesRoundTripWithoutNestedDiscriminator() {
        Map<TaskType, TaskConfig> configs = Map.of(TaskType.NOOP, new NoOpTaskConfig(), TaskType.DELAY, new DelayTaskConfig(10),
                TaskType.HTTP_REQUEST, new HttpRequestTaskConfig("GET", "https://example.test/health", 1000, null, null),
                TaskType.TRANSFORM, new TransformTaskConfig("JSON_SELECT", "{\"id\":42}", List.of("id"), Map.of()));
        configs.forEach((type, config) -> assertThat(codec.decode(type, codec.encode(config))).isEqualTo(config));
        assertThat(codec.encode(new NoOpTaskConfig())).isEqualTo("{}");
    }
    @Test void unknownConfigFieldRejectedEvenWithPermissiveHttpMapper() {
        var permissive = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        assertThatThrownBy(() -> permissive.readValue("{\"durationMillis\":1,\"command\":\"unsafe\"}", TaskConfig.class))
                .isInstanceOf(RuntimeException.class);
    }
    @Test void mixedConfigShapesRejected() {
        assertThatThrownBy(() -> codec.decode(TaskType.DELAY, "{\"durationMillis\":1,\"url\":\"https://example.test\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void mismatchedTypeRejected() {
        assertThatThrownBy(() -> codec.decode(TaskType.HTTP_REQUEST, "{\"durationMillis\":1}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"{\"durationMillis\":1.5}", "{\"durationMillis\":\"1\"}"})
    void codecDoesNotCoerceNumericValues(String config) {
        assertThatThrownBy(() -> codec.decode(TaskType.DELAY, config)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unsupportedTaskTypeCannotDeserialize() {
        assertThatThrownBy(() -> JsonMapper.builder().build().readValue("\"SHELL\"", TaskType.class)).isInstanceOf(RuntimeException.class);
    }
    @ParameterizedTest @ValueSource(longs = {-1, 30001, Long.MAX_VALUE})
    void invalidDelayRejected(long duration) { assertThatThrownBy(() -> validators.validate(TaskType.DELAY, new DelayTaskConfig(duration))).isInstanceOf(IllegalArgumentException.class); }
    @Test void delayBoundariesAccepted() {
        validators.validate(TaskType.DELAY, new DelayTaskConfig(0));
        validators.validate(TaskType.DELAY, new DelayTaskConfig(30000));
    }
    @ParameterizedTest @ValueSource(strings = {"file:///tmp/x", "ftp://example.test", "https://fixture-user@example.test", "http://example.test:0", "%%%", "https://example.test/#secret", "https://example.test/?token=test"})
    void unsafeUrlShapeRejected(String url) {
        assertThatThrownBy(() -> validators.validate(TaskType.HTTP_REQUEST, new HttpRequestTaskConfig("GET", url, 1000, null, null))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unsupportedMethodRejected() {
        assertThatThrownBy(() -> validators.validate(TaskType.HTTP_REQUEST, new HttpRequestTaskConfig("DELETE", "https://example.test", 1000, null, null))).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"http://localhost", "http://127.0.0.1", "http://10.1.1.1", "http://[::1]", "http://2130706433", "http://0177.0.0.1", "http://0x7f000001"})
    void unsafeLiteralTargetRejectedByDefault(String url) {
        assertThatThrownBy(() -> validators.validate(TaskType.HTTP_REQUEST, new HttpRequestTaskConfig("GET", url, 1000, null, null))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void privateOptInStillBlocksMetadataAndLinkLocal() {
        var optIn = new TaskProperties(properties.delay(), new TaskProperties.Http(Duration.ofSeconds(10), 16384, 65536, true), properties.transform());
        var validator = new HttpRequestTaskConfigValidator(optIn);
        validator.validate(new HttpRequestTaskConfig("GET", "http://127.0.0.1", 1000, null, null));
        validator.validate(new HttpRequestTaskConfig("GET", "http://10.1.1.1", 1000, null, null));
        for (String target : List.of("169.254.169.254", "168.63.129.16", "100.100.100.200", "[fe80::1]", "0.0.0.0", "[::ffff:168.63.129.16]", "[::ffff:100.100.100.200]", "[fd00:ec2::254]"))
            assertThatThrownBy(() -> validator.validate(new HttpRequestTaskConfig("GET", "http://" + target, 1000, null, null))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void timeoutMustBeExplicitAndBounded() {
        for (Integer timeout : new Integer[]{null, 0, 10001}) assertThatThrownBy(() -> validators.validate(TaskType.HTTP_REQUEST,
                new HttpRequestTaskConfig("GET", "https://example.test", timeout, null, null))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void requestByteLimitUsesUtf8() {
        assertThatThrownBy(() -> validators.validate(TaskType.HTTP_REQUEST, new HttpRequestTaskConfig("POST", "https://example.test", 1000, "application/json", "é".repeat(9000))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void escapedConfigCannotExceedPersistenceLimit() {
        var limits = new TaskProperties(properties.delay(), new TaskProperties.Http(Duration.ofSeconds(10), 65536, 65536, false), properties.transform());
        var registry = new TaskCapabilityValidators(List.of(new NoOpTaskConfigValidator(), new DelayTaskConfigValidator(limits),
                new HttpRequestTaskConfigValidator(limits), new TransformTaskConfigValidator(limits)));
        assertThatThrownBy(() -> registry.validate(TaskType.HTTP_REQUEST,
                new HttpRequestTaskConfig("POST", "https://example.test", 1000, "application/json", "\u0001".repeat(12000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("configuration exceeds");
    }
    @Test void duplicateRegistryEntryRejected() {
        assertThatThrownBy(() -> new TaskCapabilityValidators(List.of(new NoOpTaskConfigValidator(), new NoOpTaskConfigValidator())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");
    }
    @Test void missingRegistryEntryRejected() { assertThatThrownBy(() -> new TaskCapabilityValidators(List.of())).isInstanceOf(IllegalStateException.class); }
    @Test void transformRejectsScriptsAndNestedPaths() {
        assertThatThrownBy(() -> validators.validate(TaskType.TRANSFORM, new TransformTaskConfig("JAVASCRIPT", "{}", List.of(), Map.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validators.validate(TaskType.TRANSFORM, new TransformTaskConfig("JSON_SELECT", "{}", List.of("${exec()}"), Map.of()))).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"[]", "null", "{broken", "42"})
    void transformRequiresJsonObject(String input) {
        assertThatThrownBy(() -> validators.validate(TaskType.TRANSFORM, new TransformTaskConfig("JSON_SELECT", input, List.of("id"), Map.of()))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void duplicateRenameTargetsRejected() {
        assertThatThrownBy(() -> validators.validate(TaskType.TRANSFORM, new TransformTaskConfig("JSON_RENAME", "{}", List.of(), Map.of("a", "same", "b", "same"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void configBindsDefaultsAndRegistersValidators() {
        new ApplicationContextRunner().withUserConfiguration(CapabilityConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TaskProperties.class)).isEqualTo(properties);
            assertThat(context.getBean(TaskCapabilityCatalog.class).capabilities()).hasSize(4);
        });
    }
    @Test void invalidConfigFailsStartup() {
        new ApplicationContextRunner().withUserConfiguration(CapabilityConfiguration.class)
                .withPropertyValues("conductor.tasks.http.max-timeout=60s").run(context -> assertThat(context).hasFailed());
    }
    @Test void privateAddressOptInBindsExplicitly() {
        new ApplicationContextRunner().withUserConfiguration(CapabilityConfiguration.class)
                .withPropertyValues("conductor.tasks.http.allow-private-addresses=true", "conductor.tasks.delay.max-duration=5s")
                .run(context -> {
                    assertThat(context.getBean(TaskProperties.class).http().allowPrivateAddresses()).isTrue();
                    assertThat(context.getBean(TaskProperties.class).delay().maxDuration()).isEqualTo(Duration.ofSeconds(5));
                });
    }
}
