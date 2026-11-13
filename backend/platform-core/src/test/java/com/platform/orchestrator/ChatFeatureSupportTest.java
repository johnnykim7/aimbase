package com.platform.orchestrator;

import com.platform.attachment.AttachmentService;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import com.platform.service.PromptTemplateService;
import com.platform.tool.ToolFilterContext;
import com.platform.tool.builtin.DataQueryTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CR-183 TC-CHT-098(API 와 CLI 도구 붙이기 조건), TC-CHT-099(render_features 안내). */
class ChatFeatureSupportTest {

    private AttachmentService attachments;
    private PromptTemplateService templates;
    private ChatFeatureSupport support;

    @BeforeEach
    void setUp() {
        attachments = mock(AttachmentService.class);
        templates = mock(PromptTemplateService.class);
        support = new ChatFeatureSupport(attachments, templates);
    }

    private static String text(UnifiedMessage m) {
        return ((ContentBlock.Text) m.content().get(0)).text();
    }

    private static ChatRequest request(boolean dataTools, ToolFilterContext filter) {
        ChatRequest req = mock(ChatRequest.class);
        when(req.dataTools()).thenReturn(dataTools);
        when(req.toolFilter()).thenReturn(filter);
        return req;
    }

    @Test
    @DisplayName("API, CLI 모두 세션에 데이터 파일이 있으면 붙이고, 없으면 붙이지 않는다(CR-182 3.2.2)")
    void api_dependsOnDataFile() {
        when(attachments.hasActiveDataFile("s1")).thenReturn(true);
        when(attachments.hasActiveDataFile("s2")).thenReturn(false);

        assertThat(ChatFeatureSupport.attachesDataQuery(support.attachDataTools(request(true, null), "s1")))
                .isTrue();
        assertThat(ChatFeatureSupport.attachesDataQuery(support.attachDataTools(request(true, null), "s2")))
                .isFalse();
    }

    @Test
    @DisplayName("exclude_tools 에 data_query 가 있으면 붙이지 않는다")
    void excluded_notAttached() {
        when(attachments.hasActiveDataFile("s1")).thenReturn(true);
        ToolFilterContext filter = new ToolFilterContext(null, List.of(DataQueryTool.NAME), null, null, null, null);

        ToolFilterContext out = support.attachDataTools(request(true, filter), "s1");

        assertThat(out).isSameAs(filter);
    }

    @Test
    @DisplayName("워크플로우 단계 요청은 파일을 보지 않고 붙이지 않는다")
    void workflowStep_notAttached() {
        assertThat(ChatFeatureSupport.attachesDataQuery(support.attachDataTools(request(false, null), "s1")))
                .isFalse();
        verify(attachments, never()).hasActiveDataFile(anyString());
    }

    @Test
    @DisplayName("도구를 끈 대화의 계산 전용 목록은 data_query 하나만 허용한다")
    void dataQueryOnly() {
        when(attachments.hasActiveDataFile("s1")).thenReturn(true);
        ToolFilterContext attached = support.attachDataTools(request(true, null), "s1");

        ToolFilterContext only = ChatFeatureSupport.dataQueryOnly(attached);

        assertThat(only.allowedTools()).containsExactly(DataQueryTool.NAME);
        assertThat(only.attaches(DataQueryTool.NAME)).isTrue();
    }

    @Test
    @DisplayName("[chart, math] 는 두 틀이 마지막 시스템 안내 바로 뒤에")
    void renderGuides_appendedAfterLastSystem() {
        when(templates.getTemplate("widget.render.chart")).thenReturn("CHART");
        when(templates.getTemplate("widget.render.math")).thenReturn("MATH");
        List<UnifiedMessage> messages = List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, "base"),
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "q"));

        List<UnifiedMessage> out = support.withRenderGuides(messages, List.of("chart", "math"));

        assertThat(out).hasSize(3);
        assertThat(out.get(1).role()).isEqualTo(UnifiedMessage.Role.SYSTEM);
        assertThat(text(out.get(1))).isEqualTo("\n\nCHART\n\nMATH");
        assertThat(out.get(2).role()).isEqualTo(UnifiedMessage.Role.USER);
    }

    @Test
    @DisplayName("[chart, chart, unknown] 은 chart 한 번, 틀이 비활성이면 붙이지 않는다")
    void renderGuides_dedupAndMissing() {
        when(templates.getTemplate("widget.render.chart")).thenReturn("CHART");
        List<UnifiedMessage> messages = List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "q"));

        List<UnifiedMessage> out = support.withRenderGuides(messages, List.of("chart", "chart", "unknown"));
        assertThat(out).hasSize(2);
        assertThat(text(out.get(0))).isEqualTo("CHART");

        assertThat(support.withRenderGuides(messages, List.of("unknown"))).isSameAs(messages);
    }

    @Test
    @DisplayName("CR-187 실행기 대화면 파일 위치 안내가 이번 질문 몫으로 마지막 질문 바로 앞에")
    void artifactGuide_insertedBeforeLastUserAsTurnScoped() {
        when(templates.getTemplate("widget.artifact-out")).thenReturn("  OUT  ");
        List<UnifiedMessage> messages = List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, "base"),
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "q1"),
                UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, "a1"),
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "q2"));

        List<UnifiedMessage> out = support.withArtifactGuide(messages, true);

        assertThat(out).hasSize(5);
        assertThat(out.get(3).role()).isEqualTo(UnifiedMessage.Role.SYSTEM);
        assertThat(out.get(3).turnScoped()).isTrue();
        assertThat(text(out.get(3))).isEqualTo("OUT");
        assertThat(text(out.get(4))).isEqualTo("q2");
    }

    @Test
    @DisplayName("CR-187 대상이 아니거나 질문이 없거나 틀이 없으면 받은 목록 그대로")
    void artifactGuide_skipped() {
        List<UnifiedMessage> messages = List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "q"));
        assertThat(support.withArtifactGuide(messages, false)).isSameAs(messages);
        verify(templates, never()).getTemplate(anyString());

        assertThat(support.withArtifactGuide(messages, true)).isSameAs(messages);

        when(templates.getTemplate("widget.artifact-out")).thenReturn("OUT");
        List<UnifiedMessage> toolLoop = List.of(UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, "base"));
        assertThat(support.withArtifactGuide(toolLoop, true)).isSameAs(toolLoop);
    }
}
