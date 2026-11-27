package com.platform.config;

import com.platform.api.KnowledgeWidgetGuard;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CR-206 BIZ-291: 지식 자료 API 에 위젯 토큰 가로채기를 붙인다. 청크 원문 조회 경로만 뺀다. */
@Configuration
public class KnowledgeWidgetGuardConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(new KnowledgeWidgetGuard())
                .addPathPatterns(KnowledgeWidgetGuard.PATH)
                .excludePathPatterns(KnowledgeWidgetGuard.CHUNK_PATH);
    }
}
