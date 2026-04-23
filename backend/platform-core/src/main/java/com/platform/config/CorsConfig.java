package com.platform.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * CR-058: Chat Widget SDK 용 CORS 설정.
 *
 * widget.allowed-origins (global_config, CSV) 에 등록된 전역 화이트리스트를 기준으로
 * 위젯/세션/RAG/워크플로우 관련 엔드포인트에 한해 CORS 를 허용한다.
 * 관리 API(/api/v1/platform/**, /api/v1/admin/**)는 이 설정의 적용 범위에서 제외된다.
 *
 * 테넌트별 동적 허용은 본 CR 범위 밖 — 현재 TenantContext 는 CorsFilter 시점에
 * 설정 보장이 없어 전역 CSV 로 단순화했다 (필요 시 후속 CR).
 */
@Configuration
public class CorsConfig {

    private static final List<String> WIDGET_PATHS = List.of(
            "/api/v1/chat/**",
            "/api/v1/conversations/**",
            "/api/v1/workflows/**",
            "/api/v1/knowledge-sources/**",
            "/api/v1/sessions/issue-widget-token"
    );

    private final PlatformSettingsService settings;

    public CorsConfig(PlatformSettingsService settings) {
        this.settings = settings;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource() {
            @Override
            @Nullable
            public CorsConfiguration getCorsConfiguration(@NonNull HttpServletRequest request) {
                CorsConfiguration base = super.getCorsConfiguration(request);
                if (base == null) return null;

                // 매 요청마다 allowed-origins 를 조회 (PlatformSettingsService 내부 5분 캐시).
                List<String> allowed = settings.getStringList("widget.allowed-origins", List.of());
                CorsConfiguration cfg = new CorsConfiguration(base);
                if (allowed.isEmpty()) {
                    // 화이트리스트 비어 있으면 모든 Origin 거부.
                    cfg.setAllowedOrigins(List.of());
                } else {
                    cfg.setAllowedOrigins(allowed);
                }
                return cfg;
            }
        };

        CorsConfiguration template = new CorsConfiguration();
        template.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        template.setAllowedHeaders(List.of(
                "Authorization", "X-API-Key", "X-Tenant-Id",
                "Content-Type", "Accept", "Last-Event-ID"));
        template.setExposedHeaders(List.of("Cache-Control", "Connection"));
        template.setAllowCredentials(false); // 토큰 방식만 사용 (쿠키 미사용)
        template.setMaxAge(Duration.ofMinutes(10));

        for (String path : WIDGET_PATHS) {
            source.registerCorsConfiguration(path, template);
        }
        return source;
    }
}
