package com.loom.worker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.loom.common.outbox.JdbcOutboxWriter;
import org.springframework.context.annotation.Import;

@Configuration
@Import(JdbcOutboxWriter.class)
@org.springframework.boot.context.properties.EnableConfigurationProperties(DemoProperties.class)
public class WorkerConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}
