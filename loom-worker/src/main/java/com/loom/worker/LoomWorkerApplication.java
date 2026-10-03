package com.loom.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EntityScan(basePackages = "com.loom.common.model")
@EnableJpaRepositories(basePackages = "com.loom.worker.repository")
@EnableScheduling
public class LoomWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(LoomWorkerApplication.class, args);
    }
}
