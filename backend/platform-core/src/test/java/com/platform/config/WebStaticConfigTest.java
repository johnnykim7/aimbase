package com.platform.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.File;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class WebStaticConfigTest {

    private AnnotationConfigWebApplicationContext ctx;
    private MockMvc mvc;

    @Configuration
    @EnableWebMvc
    static class Mvc {
    }

    @BeforeEach
    void setUp() {
        ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.register(Mvc.class, WebStaticConfig.class);
        ctx.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    private MvcResult fetch(String path) throws Exception {
        return mvc.perform(get(path)).andReturn();
    }

    @Test
    @DisplayName("IT-09-025 1 위젯_진입_파일은_열_때마다_바뀌었는지_묻는다")
    void entryFilesRevalidateEveryTime() throws Exception {
        for (String f : new String[] {"aimbase-chat.umd.global.js", "aimbase-chat.esm.js", "aimbase-chat.d.ts", "guide.html"}) {
            MvcResult r = fetch("/widget/v1/" + f);
            assertThat(r.getResponse().getStatus()).as(f).isEqualTo(200);
            assertThat(r.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).as(f).isEqualTo("no-cache");
        }
    }

    @Test
    @DisplayName("IT-09-025 2 내용_해시가_이름에_붙은_코드_조각은_1년_캐시한다")
    void hashedChunksAreImmutable() throws Exception {
        File dir = new File("src/main/resources/static/widget/v1");
        String[] hashed = dir.list((d, n) -> n.matches("[^/]+-[A-Z0-9]{8}\\.js"));
        assertThat(hashed).isNotEmpty();
        for (String f : Arrays.copyOf(hashed, Math.min(hashed.length, 5))) {
            MvcResult r = fetch("/widget/v1/" + f);
            assertThat(r.getResponse().getStatus()).as(f).isEqualTo(200);
            assertThat(r.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).as(f)
                    .isEqualTo("max-age=31536000, public, immutable");
            assertThat(r.getResponse().getContentAsByteArray()).as(f).isNotEmpty();
        }
    }
}
