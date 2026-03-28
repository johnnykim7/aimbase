package com.platform.config;

import com.platform.tenant.TenantDataSourceManager;
import com.platform.tenant.TenantRoutingDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 이중 DataSource / EntityManagerFactory 설정.
 *
 * - Master DB: tenants, subscriptions 등 플랫폼 메타 테이블
 *   → masterDataSource → masterEntityManagerFactory → masterTransactionManager
 *   → com.platform.repository.master.*
 *
 * - Tenant DB: connections, policies, workflows 등 테넌트별 테이블
 *   → TenantRoutingDataSource (tenant_id 기반 동적 라우팅)
 *   → tenantEntityManagerFactory (@Primary)
 *   → com.platform.repository.* (master 제외)
 */
@Configuration
public class TenantDataSourceConfig {

    // ─── Master DataSource ───────────────────────────────────────────

    @Bean(name = "masterDataSourceProperties")
    @ConfigurationProperties("spring.datasource.master")
    public DataSourceProperties masterDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "masterDataSource")
    public DataSource masterDataSource(
            @Qualifier("masterDataSourceProperties") DataSourceProperties props) {
        return props.initializeDataSourceBuilder().build();
    }

    @Bean(name = "masterJdbcTemplate")
    public JdbcTemplate masterJdbcTemplate(@Qualifier("masterDataSource") DataSource masterDataSource) {
        return new JdbcTemplate(masterDataSource);
    }

    @Bean(name = "masterEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean masterEntityManagerFactory(
            @Qualifier("masterDataSource") DataSource masterDataSource) {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(masterDataSource);
        em.setPackagesToScan("com.platform.domain.master");
        em.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        em.setJpaProperties(hibernateProperties());
        em.setPersistenceUnitName("master");
        return em;
    }

    @Bean(name = "masterTransactionManager")
    public PlatformTransactionManager masterTransactionManager(
            @Qualifier("masterEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    // ─── Tenant (Routing) DataSource ─────────────────────────────────

    @Bean(name = "tenantDataSource")
    @Primary
    @DependsOn("masterDataSource")
    public DataSource tenantDataSource(TenantDataSourceManager manager,
                                        @Qualifier("masterDataSource") DataSource masterDataSource) {
        // 기존 활성 테넌트 DataSource를 manager 캐시에 미리 로드
        manager.loadAllTenantDataSources();

        TenantRoutingDataSource routing = new TenantRoutingDataSource();
        // manager 직접 조회 방식 — afterPropertiesSet() 이후 동적으로 추가된 테넌트도 즉시 라우팅
        routing.configure(manager, masterDataSource);

        // AbstractRoutingDataSource.afterPropertiesSet() 요건 충족 (빈 맵 + default 설정)
        routing.setTargetDataSources(Map.of());
        routing.setDefaultTargetDataSource(masterDataSource);
        routing.afterPropertiesSet();
        return routing;
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("tenantDataSource") DataSource tenantDataSource) {
        return new JdbcTemplate(tenantDataSource);
    }

    @Bean(name = "tenantEntityManagerFactory")
    @Primary
    public LocalContainerEntityManagerFactoryBean tenantEntityManagerFactory(
            @Qualifier("tenantDataSource") DataSource tenantDataSource) {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(tenantDataSource);
        // master 패키지 제외 — 테넌트 DB 전용 엔티티만
        em.setPackagesToScan("com.platform.domain");
        em.setJpaVendorAdapter(new HibernateJpaVendorAdapter());

        Properties props = hibernateProperties();
        // 테넌트별 DataSource가 동적으로 변경되므로 DDL 자동 검증 비활성
        props.setProperty("hibernate.hbm2ddl.auto", "none");
        em.setJpaProperties(props);
        em.setPersistenceUnitName("tenant");
        return em;
    }

    @Bean(name = "transactionManager")
    @Primary
    public PlatformTransactionManager transactionManager(
            @Qualifier("tenantEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    // ─── 공통 Hibernate 설정 ─────────────────────────────────────────

    private Properties hibernateProperties() {
        Properties props = new Properties();
        props.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        props.setProperty("hibernate.hbm2ddl.auto", "none");
        props.setProperty("hibernate.show_sql", "false");
        props.setProperty("hibernate.format_sql", "true");
        props.setProperty("hibernate.jdbc.batch_size", "20");
        // vladmihalcea hibernate-types 가 classpath에 Jackson이 있으면 자동으로 Jackson 사용
        // hibernate.type.json_format_mapper 는 Hibernate 6 내부 설정과 충돌하므로 제거
        return props;
    }
}
