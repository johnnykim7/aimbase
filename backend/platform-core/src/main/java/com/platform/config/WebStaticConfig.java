package com.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

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
 * 진입 파일(로더, esm, 타입, 안내 페이지)은 이름이 판마다 같아 오래 캐시하면 배포한 수정이
 * 사용자 브라우저에 닿지 않는다(CR-175). 그래서 열 때마다 Last-Modified 로 바뀌었는지 묻게 한다.
 * 코드 조각은 빌드가 내용 해시를 이름에 붙이므로 내용이 바뀌면 이름도 바뀐다. 그것만 오래 둔다.
 */
@Configuration
public class WebStaticConfig implements WebMvcConfigurer {

    /** tsup(esbuild) 가 코드 조각 이름 끝에 붙이는 내용 해시: 대문자와 숫자 8자. */
    private static final String HASHED_CHUNK_PATTERN = "/widget/v1/{file:.+-[A-Z0-9]{8}\\.js}";

    private static final long HASHED_CHUNK_CACHE_DAYS = 365;

    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        registry.addResourceHandler(HASHED_CHUNK_PATTERN)
                .addResourceLocations("classpath:/static/widget/v1/")
                .setCacheControl(CacheControl.maxAge(HASHED_CHUNK_CACHE_DAYS, TimeUnit.DAYS).cachePublic().immutable());
        registry.addResourceHandler("/widget/**")
                .addResourceLocations("classpath:/static/widget/")
                .setCacheControl(CacheControl.noCache());
    }

    /** 루트 디렉토리 URL 을 index.html 로 포워드한다. */
    @Override
    public void addViewControllers(@NonNull ViewControllerRegistry registry) {
        registry.addViewController("/widget/v1/").setViewName("forward:/widget/v1/index.html");
        registry.addViewController("/widget/v1").setViewName("forward:/widget/v1/index.html");
    }
}
