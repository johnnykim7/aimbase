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
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import jakarta.servlet.DispatcherType;

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
@EnableMethodSecurity(prePostEnabled = true)
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
            .cors(Customizer.withDefaults())  // CR-058: CorsConfigurationSource bean 사용
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // CR-082 root fix (2026-04-30): ASYNC dispatch (SseEmitter.complete/completeWithError 후의 재진입)
                // 는 권한 재검사를 건너뛴다. 이미 첫 진입 시 jwtFilter 가 인증 통과한 동일 요청이며,
                // VT 종료 시점의 SecurityContextHolder 가 비어 있어 AuthorizationFilter 가 거부 → 403 으로 응답하는
                // race 가 위젯에 "network error" 로 노출되는 문제를 차단한다.
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/**", "/swagger-ui/**", "/api-docs/**", "/ws/**").permitAll()
                // CR-058: 위젯 번들·가이드 정적 리소스 — 인증 없이 공개 (소비앱이 CDN 처럼 소비)
                .requestMatchers("/widget/**").permitAll()
                // 기타 SSE / 관리 MCP — 인증 없음
                .requestMatchers("/sse/**", "/admin-mcp/**").permitAll()
                // CR-072: 서버 도구 MCP endpoint — X-API-Key 인증 (ApiKeyAuthenticationFilter 가 처리)
                .requestMatchers("/mcp/**").authenticated()
                // 인증 엔드포인트
                .requestMatchers("/api/v1/auth/**").permitAll()
                // 사이드카 토큰 조회 (인증 없이 접근 가능 — 사이드카 기동 시 호출)
                .requestMatchers("/api/v1/platform/agent-accounts/*/token").permitAll()
                // RBAC
                .requestMatchers("/api/v1/platform/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/v1/apps/*/auth/**").permitAll()
                .requestMatchers("/api/v1/apps/**").authenticated()
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
