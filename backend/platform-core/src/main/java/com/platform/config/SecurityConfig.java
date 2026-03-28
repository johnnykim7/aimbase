package com.platform.config;

import com.platform.auth.ApiKeyAuthenticationFilter;
import com.platform.auth.JwtAuthenticationFilter;
import com.platform.auth.JwtProvider;
import com.platform.repository.master.ApiKeyRepository;
import com.platform.repository.RoleRepository;
import com.platform.repository.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Sprint 22: JWT + API Key 인증, RBAC 적용.
 *
 * Multi-Tenancy v4:
 * - /api/v1/platform/** → ROLE_SUPER_ADMIN 전용
 * - /api/v1/admin/** → ROLE_ADMIN 이상
 * - TenantResolver Filter는 @Order(1)로 SecurityFilterChain 전에 실행됨
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ApiKeyRepository apiKeyRepository;

    public SecurityConfig(JwtProvider jwtProvider,
                          UserRepository userRepository,
                          RoleRepository roleRepository,
                          ApiKeyRepository apiKeyRepository) {
        this.jwtProvider = jwtProvider;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.apiKeyRepository = apiKeyRepository;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnProperty(name = "security.enabled", havingValue = "true", matchIfMissing = true)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        var jwtFilter = new JwtAuthenticationFilter(jwtProvider, userRepository, roleRepository);
        var apiKeyFilter = new ApiKeyAuthenticationFilter(userRepository, roleRepository, apiKeyRepository);

        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**", "/swagger-ui/**", "/api-docs/**", "/ws/**").permitAll()
                // MCP SSE 엔드포인트
                .requestMatchers("/sse/**", "/mcp/**", "/admin-mcp/**").permitAll()
                // 인증 엔드포인트
                .requestMatchers("/api/v1/auth/**").permitAll()
                // RBAC
                .requestMatchers("/api/v1/platform/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/v1/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                // 그 외 API — 인증 필요
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().permitAll()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(apiKeyFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}
