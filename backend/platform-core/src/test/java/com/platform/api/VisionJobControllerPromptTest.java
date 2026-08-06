package com.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.attachment.MimeValidator;
import com.platform.service.PromptTemplateService;
import com.platform.vision.VisionJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CR-140: vision-jobs 프롬프트 확정 규칙.
 *
 * <p>판독 지시문을 소비앱 코드에 박지 않고 aimbase 템플릿(prompt_key)에서 가져오는 경로.
 * 확정 로직만 떼어 검증한다 — 업로드·MIME·저장은 CR-137 쪽 테스트가 덮는다.
 */
class VisionJobControllerPromptTest {

    private PromptTemplateService promptTemplateService;
    private VisionJobController controller;

    @BeforeEach
    void setUp() {
        promptTemplateService = mock(PromptTemplateService.class);
        controller = new VisionJobController(
                mock(VisionJobService.class),
                mock(MimeValidator.class),
                promptTemplateService,
                new ObjectMapper());
    }

    /** resolvePrompt 는 private — 확정 규칙 자체가 검증 대상이라 리플렉션으로 직접 부른다. */
    private String resolve(String prompt, String key, String vars) {
        return (String) ReflectionTestUtils.invokeMethod(
                controller, "resolvePrompt", prompt, key, vars);
    }

    @Test
    void promptKey로_템플릿을_렌더해_쓴다() {
        when(promptTemplateService.render(eq("vision.return.grade"), any()))
                .thenReturn("반품 검수. JSON만 출력.");

        assertThat(resolve(null, "vision.return.grade", null))
                .isEqualTo("반품 검수. JSON만 출력.");
    }

    /** 템플릿 변수는 JSON 으로 받아 그대로 넘긴다. */
    @Test
    void promptVars가_렌더에_전달된다() {
        when(promptTemplateService.render(anyString(), any())).thenReturn("의류 검수");

        resolve(null, "vision.return.grade", "{\"category\":\"의류\"}");

        verify(promptTemplateService).render("vision.return.grade", Map.of("category", "의류"));
    }

    /** 일회성 판독·실험을 템플릿 등록 없이 할 수 있어야 하므로 원문이 이긴다. */
    @Test
    void 원문과_key가_함께오면_원문이_이긴다() {
        assertThat(resolve("직접 쓴 지시문", "vision.return.grade", null))
                .isEqualTo("직접 쓴 지시문");

        verifyNoInteractions(promptTemplateService);
    }

    /** 조용히 빈 프롬프트로 판독하면 엉뚱한 결과가 나온다 — 키 오타를 요청 시점에 잡는다. */
    @Test
    void 없는_key는_거부한다() {
        when(promptTemplateService.render(anyString(), any())).thenReturn(null);

        assertThatThrownBy(() -> resolve(null, "vision.typo", null))
                .hasMessageContaining("prompt template not found")
                .hasMessageContaining("vision.typo");
    }

    /** 템플릿이 비어 있어도 마찬가지 — null 만 막으면 빈 문자열이 통과한다. */
    @Test
    void 빈_템플릿도_거부한다() {
        when(promptTemplateService.render(anyString(), any())).thenReturn("   ");

        assertThatThrownBy(() -> resolve(null, "vision.empty", null))
                .hasMessageContaining("prompt template not found or empty");
    }

    @Test
    void 잘못된_promptVars는_거부한다() {
        assertThatThrownBy(() -> resolve(null, "vision.return.grade", "not-json"))
                .hasMessageContaining("prompt_vars is not a valid JSON object");
    }

    /** 둘 다 없으면 null — 서비스 기본 프롬프트로 판독한다(기존 동작 유지). */
    @Test
    void 둘다_없으면_null() {
        assertThat(resolve(null, null, null)).isNull();
        assertThat(resolve("  ", "  ", null)).isEqualTo("  ");
    }
}
