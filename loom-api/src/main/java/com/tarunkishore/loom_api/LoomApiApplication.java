package com.tarunkishore.loom_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan(basePackages = {"com.loom.common.model", "com.tarunkishore.loom_api.ai"})
@EnableJpaRepositories(basePackages = "com.tarunkishore.loom_api.repository")
public class LoomApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoomApiApplication.class, args);
    }
}
