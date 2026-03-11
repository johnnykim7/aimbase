package com.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Master DB Repository 설정.
 * com.platform.repository.master 패키지를 masterEntityManagerFactory로 연결.
 */
@Configuration
@EnableJpaRepositories(
    basePackages = "com.platform.repository.master",
    entityManagerFactoryRef = "masterEntityManagerFactory",
    transactionManagerRef = "masterTransactionManager"
)
public class MasterRepositoryConfig {
}
