package com.loom.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TaskDataflowContractsTest {
    private final TaskProperties properties = TaskProperties.defaults();
    private final TransformTaskConfigValidator transforms = new TransformTaskConfigValidator(properties);
    private final HttpRequestTaskConfigValidator http = new HttpRequestTaskConfigValidator(properties);
    private final TaskDataflowValidator dataflow = new TaskDataflowValidator();

    @Test void legacyHttpJsonDefaultsToDiscardAndLegacyTransformRemainsStatic() {
        var validators = new TaskCapabilityValidators(List.of(new NoOpTaskConfigValidator(),
                new DelayTaskConfigValidator(properties), http, transforms));
        var codec = new TaskConfigCodec(validators);
        var config = (HttpRequestTaskConfig) codec.decode(TaskType.HTTP_REQUEST,
                "{\"method\":\"GET\",\"url\":\"https://example.test\",\"timeoutMillis\":100}");
        assertThat(config.captureResponseBody()).isFalse();
        assertThat(config.bodyTaskId()).isNull();
        assertThat(codec.decode(TaskType.TRANSFORM, "{\"operation\":\"JSON_SELECT\",\"input\":\"{}\",\"fields\":[\"id\"]}"))
                .isEqualTo(new TransformTaskConfig("JSON_SELECT", "{}", List.of("id"), Map.of()));
    }

    @Test void referenceConfigsRoundTripStrictly() {
        var validators = new TaskCapabilityValidators(List.of(new NoOpTaskConfigValidator(),
                new DelayTaskConfigValidator(properties), http, transforms));
        var codec = new TaskConfigCodec(validators);
        var transform = transform("fetch");
        var post = post(null, "select");
        assertThat(codec.decode(TaskType.TRANSFORM, codec.encode(transform))).isEqualTo(transform);
        assertThat(codec.decode(TaskType.HTTP_REQUEST, codec.encode(post))).isEqualTo(post);
        var capture = new HttpRequestTaskConfig("GET", "https://example.test", 100, null, null, true, null);
        assertThat(codec.decode(TaskType.HTTP_REQUEST, codec.encode(capture))).isEqualTo(capture);
    }

    @Test void transformRequiresExactlyOneSource() {
        assertThatThrownBy(() -> transforms.validate(new TransformTaskConfig("JSON_SELECT", null, List.of("id"), Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transforms.validate(new TransformTaskConfig("JSON_SELECT", "{}", List.of("id"), Map.of(), "fetch")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> transforms.validate(transform("fetch"))).doesNotThrowAnyException();
    }

    @Test void postCannotCombineStaticBodyAndReference() {
        assertThatThrownBy(() -> http.validate(post("{}", "fetch"))).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> http.validate(post(null, null))).doesNotThrowAnyException();
    }

    @Test void getCannotConsumeBodyReference() {
        assertThatThrownBy(() -> http.validate(new HttpRequestTaskConfig("GET", "https://example.test", 100,
                "application/json", null, false, "fetch"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void referencedPostRequiresJsonContentType() {
        assertThatThrownBy(() -> http.validate(new HttpRequestTaskConfig("POST", "https://example.test", 100,
                null, null, false, "fetch"))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "${tasks.fetch.output}", "fetch.id", "a/b"})
    void referencesDoNotAcceptExpressionsOrPaths(String reference) {
        assertThatThrownBy(() -> transforms.validate(transform(reference))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void directDependencyReferenceAccepted() {
        dataflow.validate(List.of(task("fetch", List.of(), new NoOpTaskConfig()), task("select", List.of("fetch"), transform("fetch"))));
    }

    @Test void unknownReferenceRejected() {
        assertThatThrownBy(() -> dataflow.validate(List.of(task("select", List.of("unknown"), transform("unknown")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not exist");
    }

    @Test void selfReferenceRejected() {
        assertThatThrownBy(() -> dataflow.validate(List.of(task("select", List.of("select"), transform("select")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("itself");
    }

    @Test void ancestorReferenceRejected() {
        assertThatThrownBy(() -> dataflow.validate(List.of(task("a", List.of(), new NoOpTaskConfig()),
                task("b", List.of("a"), new NoOpTaskConfig()), task("c", List.of("b"), transform("a")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("direct dependency");
    }

    @Test void siblingReferenceRejected() {
        assertThatThrownBy(() -> dataflow.validate(List.of(task("a", List.of(), new NoOpTaskConfig()),
                task("b", List.of(), transform("a")), task("c", List.of("a", "b"), new NoOpTaskConfig()))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("direct dependency");
    }

    @Test void staticInputsNeedNoReferenceValidation() {
        dataflow.validate(List.of(task("select", List.of(), new TransformTaskConfig("JSON_SELECT", "{}", List.of("id"), Map.of())),
                task("post", List.of(), post("{}", null))));
    }

    @Test void outputUsesUtf8BytesAndNeverTruncates() {
        TaskOutputLimits.validate("é", TaskOutputLimits.TEXT, 2);
        assertThatThrownBy(() -> TaskOutputLimits.validate("é", TaskOutputLimits.TEXT, 1))
                .isInstanceOf(IllegalArgumentException.class);
        TaskOutputLimits.validate("a".repeat(TaskOutputLimits.MAX_BYTES), TaskOutputLimits.TEXT, TaskOutputLimits.MAX_BYTES);
        assertThatThrownBy(() -> TaskOutputLimits.validate("a".repeat(TaskOutputLimits.MAX_BYTES + 1), TaskOutputLimits.TEXT, TaskOutputLimits.MAX_BYTES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void outputContentTypeAndPresenceMustAgree() {
        TaskOutputLimits.validate(null, null, 10);
        TaskOutputLimits.validate("", TaskOutputLimits.JSON, 10);
        assertThatThrownBy(() -> TaskOutputLimits.validate(null, TaskOutputLimits.JSON, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskOutputLimits.validate("{}", null, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskOutputLimits.validate("data", "application/octet-stream", 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void configuredOutputLimitBindsAndValidatorIsRegistered() {
        new ApplicationContextRunner().withUserConfiguration(CapabilityConfiguration.class)
                .withPropertyValues("conductor.tasks.output.max-bytes=1024").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TaskProperties.class).output().maxBytes()).isEqualTo(1024);
                    assertThat(context.getBean(TaskDataflowValidator.class)).isNotNull();
                });
    }

    @ParameterizedTest @ValueSource(ints = {0, -1, 65537})
    void invalidOutputLimitsFailBinding(int maxBytes) {
        new ApplicationContextRunner().withUserConfiguration(CapabilityConfiguration.class)
                .withPropertyValues("conductor.tasks.output.max-bytes=" + maxBytes)
                .run(context -> assertThat(context).hasFailed());
    }

    private static TransformTaskConfig transform(String reference) {
        return new TransformTaskConfig("JSON_SELECT", null, List.of("id"), Map.of(), reference);
    }
    private static HttpRequestTaskConfig post(String body, String reference) {
        return new HttpRequestTaskConfig("POST", "https://example.test", 100, "application/json", body, false, reference);
    }
    private static TaskDataflowValidator.DataflowTask task(String id, List<String> dependencies, TaskConfig config) {
        return new TaskDataflowValidator.DataflowTask(id, dependencies, config);
    }
}
