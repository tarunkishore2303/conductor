package com.loom.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan(basePackages = "com.loom.common.model")
@EnableJpaRepositories(basePackages = "com.loom.worker.repository")
@org.springframework.context.annotation.Import(com.loom.capability.CapabilityConfiguration.class)
public class LoomWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(LoomWorkerApplication.class, args);
    }
}
