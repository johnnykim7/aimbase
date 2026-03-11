package com.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Tenant DB Repository 설정.
 * com.platform.repository (master 제외) 패키지를 tenantEntityManagerFactory로 연결.
 */
@Configuration
@EnableJpaRepositories(
    basePackages = "com.platform.repository",
    excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
        type = org.springframework.context.annotation.FilterType.REGEX,
        pattern = "com\\.platform\\.repository\\.master\\..*"
    ),
    entityManagerFactoryRef = "tenantEntityManagerFactory",
    transactionManagerRef = "transactionManager"
)
public class TenantRepositoryConfig {
}
