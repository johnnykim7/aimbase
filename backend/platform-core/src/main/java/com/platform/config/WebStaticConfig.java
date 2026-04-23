package com.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CR-058: 위젯 번들·가이드 정적 리소스 서빙.
 *
 * <p>공개 URL:
 * <ul>
 *   <li>/widget/v1/aimbase-chat.umd.global.js — UMD 번들 (&lt;script&gt; 직접 삽입용)</li>
 *   <li>/widget/v1/aimbase-chat.esm.js — ESM (번들러 import 용)</li>
 *   <li>/widget/v1/aimbase-chat.d.ts — TypeScript 타입</li>
 *   <li>/widget/v1/guide.html — HTML 통합 가이드</li>
 *   <li>/widget/v1/sample-bff/ — BFF 샘플 코드</li>
 * </ul>
 *
 * 장기 캐시(30일)로 브라우저 재다운로드를 줄인다. 버전 패스(/v1/)가 고정이므로
 * 번들이 업데이트되면 새 파일명이 아니라 etag/last-modified 로 재검증된다.
 */
@Configuration
public class WebStaticConfig implements WebMvcConfigurer {

    private static final int CACHE_SECONDS = 60 * 60 * 24 * 30; // 30일

    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/widget/**")
                .addResourceLocations("classpath:/static/widget/")
                .setCachePeriod(CACHE_SECONDS);
    }

    /** 루트 디렉토리 URL 을 index.html 로 포워드한다. */
    @Override
    public void addViewControllers(@NonNull ViewControllerRegistry registry) {
        registry.addViewController("/widget/v1/").setViewName("forward:/widget/v1/index.html");
        registry.addViewController("/widget/v1").setViewName("forward:/widget/v1/index.html");
    }
}
