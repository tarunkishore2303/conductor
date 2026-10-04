package com.loom.monitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan(basePackages = "com.loom.common.model")
@EnableJpaRepositories(basePackages = "com.loom.monitor.repository")
public class LoomMonitorApplication {
    public static void main(String[] args) {
        SpringApplication.run(LoomMonitorApplication.class, args);
    }
}
