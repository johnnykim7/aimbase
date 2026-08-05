package com.platform.llm.adapter;

import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-136: OpenAI 호환 어댑터 멀티모달(이미지 블록) 전달 단위 테스트.
 *
 * <p>핵심 계약: USER 메시지에 Image 블록이 있으면 OpenAI {@code image_url} 파트를 포함한
 * content part 배열로 변환된다. 이미지가 없으면 기존 단일 문자열 경로를 그대로 유지한다.
 *
 * <p>이 어댑터는 vLLM/Ollama/LM Studio/DeepSeek/LocalAI 및
 * {@code BedrockAdapter}/{@code VertexAIAdapter} 위임 경로 전부가 공유한다.
 */
class OpenAICompatibleAdapterTest {

    /** 1x1 투명 PNG (base64). */
    private static final String PNG_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

    /** 실제 HTTP 호출은 하지 않는다 — 메시지 변환만 검증한다. */
    private final OpenAICompatibleAdapter adapter =
            new OpenAICompatibleAdapter("http://localhost:1234/v1", "test-key",
                    "qwen2.5-vl-7b-instruct", "openai_compatible");

    private ChatCompletionUserMessageParam userParam(UnifiedMessage msg) {
        ChatCompletionMessageParam param = adapter.toMessage(msg);
        assertThat(param.isUser()).isTrue();
        return param.asUser();
    }

    private List<ChatCompletionContentPart> partsOf(UnifiedMessage msg) {
        var content = userParam(msg).content();
        assertThat(content.isArrayOfContentParts())
                .as("이미지 블록이 있으면 멀티파트여야 한다")
                .isTrue();
        return content.asArrayOfContentParts();
    }

    @Test
    @DisplayName("base64 이미지 + 텍스트 → image_url(data URI) + text 파트로 변환")
    void base64ImageBecomesDataUriPart() {
        var msg = UnifiedMessage.ofUserContent(List.of(
                new ContentBlock.Text("이 선반에 박스가 몇 개인가요?"),
                ContentBlock.Image.ofBase64("image/png", PNG_B64)));

        var parts = partsOf(msg);

        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).isText()).isTrue();
        assertThat(parts.get(0).asText().text()).isEqualTo("이 선반에 박스가 몇 개인가요?");
        assertThat(parts.get(1).isImageUrl()).isTrue();
        assertThat(parts.get(1).asImageUrl().imageUrl().url())
                .isEqualTo("data:image/png;base64," + PNG_B64);
    }

    @Test
    @DisplayName("URL 이미지 → image_url 파트에 URL 그대로 전달")
    void urlImagePassesThrough() {
        var msg = UnifiedMessage.ofUserContent(List.of(
                ContentBlock.Image.ofUrl("https://example.com/rack.jpg", "image/jpeg")));

        var parts = partsOf(msg);

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).isImageUrl()).isTrue();
        assertThat(parts.get(0).asImageUrl().imageUrl().url())
                .isEqualTo("https://example.com/rack.jpg");
    }

    @Test
    @DisplayName("이미지 여러 장(영상 프레임) → 순서대로 전부 전달")
    void multipleImagesArePreservedInOrder() {
        var msg = UnifiedMessage.ofUserContent(List.of(
                new ContentBlock.Text("프레임 판독"),
                ContentBlock.Image.ofBase64("image/jpeg", "AAAA"),
                ContentBlock.Image.ofBase64("image/jpeg", "BBBB"),
                ContentBlock.Image.ofBase64("image/jpeg", "CCCC")));

        var parts = partsOf(msg);

        assertThat(parts).hasSize(4);
        assertThat(parts.stream().filter(p -> p.isImageUrl()).count()).isEqualTo(3);
        assertThat(parts.subList(1, 4).stream()
                .map(p -> p.asImageUrl().imageUrl().url())
                .toList())
                .containsExactly(
                        "data:image/jpeg;base64,AAAA",
                        "data:image/jpeg;base64,BBBB",
                        "data:image/jpeg;base64,CCCC");
    }

    @Test
    @DisplayName("data/url 이 모두 빈 이미지 블록은 건너뛴다")
    void emptyImageBlockIsSkipped() {
        var msg = UnifiedMessage.ofUserContent(List.of(
                new ContentBlock.Text("설명"),
                new ContentBlock.Image("image/png", null, null)));

        var parts = partsOf(msg);

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).isText()).isTrue();
    }

    // ─── 회귀 방지: 텍스트 전용 경로는 기존 동작 유지 ───

    @Test
    @DisplayName("[회귀] 텍스트 전용 USER → 단일 문자열 content 유지")
    void textOnlyUserStaysPlainString() {
        var msg = UnifiedMessage.ofText(UnifiedMessage.Role.USER, "안녕하세요");

        var content = userParam(msg).content();

        assertThat(content.isText()).isTrue();
        assertThat(content.asText()).isEqualTo("안녕하세요");
    }

    @Test
    @DisplayName("[회귀] 텍스트 블록 여러 개는 연결되어 단일 문자열로 유지")
    void multipleTextBlocksAreConcatenated() {
        var msg = UnifiedMessage.ofUserContent(List.of(
                new ContentBlock.Text("가"),
                new ContentBlock.Text("나")));

        var content = userParam(msg).content();

        assertThat(content.isText()).isTrue();
        assertThat(content.asText()).isEqualTo("가나");
    }

    @Test
    @DisplayName("[회귀] SYSTEM/ASSISTANT 는 변환 방식 무변경")
    void systemAndAssistantUnchanged() {
        var system = adapter.toMessage(
                UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, "너는 창고 검수 도우미다"));
        assertThat(system.isSystem()).isTrue();
        assertThat(system.asSystem().content().asText()).isEqualTo("너는 창고 검수 도우미다");

        var assistant = adapter.toMessage(
                UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, "확인했습니다"));
        assertThat(assistant.isAssistant()).isTrue();
        assertThat(assistant.asAssistant().content().orElseThrow().asText()).isEqualTo("확인했습니다");
    }

    @Test
    @DisplayName("[회귀] TOOL_RESULT 는 tool 메시지로 유지 (buildMessages 경로)")
    void toolResultStillMapsToToolMessage() {
        var messages = adapter.buildMessages(List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "재고 조회"),
                UnifiedMessage.ofToolResults(List.of(
                        new ContentBlock.ToolResult("call_1", "{\"qty\":9}")))));

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).isUser()).isTrue();
        assertThat(messages.get(1).isTool()).isTrue();
        assertThat(messages.get(1).asTool().toolCallId()).isEqualTo("call_1");
    }

    @Test
    @DisplayName("멀티모달 메시지가 buildMessages 전체 경로에서도 보존된다")
    void multimodalSurvivesBuildMessages() {
        var messages = adapter.buildMessages(List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, "system"),
                UnifiedMessage.ofUserContent(List.of(
                        new ContentBlock.Text("판독"),
                        ContentBlock.Image.ofBase64("image/png", PNG_B64)))));

        assertThat(messages).hasSize(2);
        var parts = messages.get(1).asUser().content().asArrayOfContentParts();
        assertThat(parts).hasSize(2);
        assertThat(parts.get(1).asImageUrl().imageUrl().url())
                .isEqualTo("data:image/png;base64," + PNG_B64);
    }
}
