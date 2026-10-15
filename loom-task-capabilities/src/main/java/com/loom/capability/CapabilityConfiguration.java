package com.loom.capability;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TaskProperties.class)
public class CapabilityConfiguration {
    @Bean NoOpTaskConfigValidator noOpTaskConfigValidator() { return new NoOpTaskConfigValidator(); }
    @Bean DelayTaskConfigValidator delayTaskConfigValidator(TaskProperties properties) { return new DelayTaskConfigValidator(properties); }
    @Bean HttpRequestTaskConfigValidator httpRequestTaskConfigValidator(TaskProperties properties) { return new HttpRequestTaskConfigValidator(properties); }
    @Bean TransformTaskConfigValidator transformTaskConfigValidator(TaskProperties properties) { return new TransformTaskConfigValidator(properties); }
    @Bean TaskCapabilityValidators taskCapabilityValidators(List<TaskConfigValidator> validators) { return new TaskCapabilityValidators(validators); }
    @Bean TaskConfigCodec taskConfigCodec(TaskCapabilityValidators validators) { return new TaskConfigCodec(validators); }
    @Bean TaskCapabilityCatalog taskCapabilityCatalog(TaskProperties properties) { return new TaskCapabilityCatalog(properties); }
}
