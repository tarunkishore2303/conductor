package com.loom.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("conductor.demo")
public record DemoProperties(@DefaultValue("false") boolean failuresEnabled) {}
