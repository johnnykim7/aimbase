package com.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Phase 1: 개발 편의를 위해 API 전체 허용.
 * Phase 5에서 JWT 인증 + RBAC으로 강화.
 *
 * Multi-Tenancy v4:
 * - /api/v1/platform/** → Phase 5에서 ROLE_SUPER_ADMIN 전용으로 제한 예정
 * - TenantResolver Filter는 @Order(1)로 SecurityFilterChain 전에 실행됨
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**", "/swagger-ui/**", "/api-docs/**", "/ws/**").permitAll()
                // TODO Phase 5: .requestMatchers("/api/v1/platform/**").hasRole("SUPER_ADMIN")
                .anyRequest().permitAll()  // Phase 1: 전체 허용 (Phase 5에서 변경)
            );
        return http.build();
    }
}
