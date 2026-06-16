package com.platform.llm.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-113: CLI 입구 강제 — response_schema 가 있으면 마지막 user 메시지 끝에 JSON-only 지시를 덧붙여
 * CLI 가 schema 의 JSON 만 내도록 강제하는
 * {@link ClaudeCliRunnerClient#appendSchemaDirectiveToLastUser} 검증.
 *
 * <p>CLI 는 Anthropic tool_choice / OpenAI json_schema 같은 API 레벨 구조화 강제가 없어 프롬프트로
 * 누르는 게 유일한 입구 수단이다(단독 CLI 실측으로 효과 확인). system prompt 가 아니라 user 메시지에
 * 붙이는 이유: --system-prompt 는 CLI 기본 프롬프트를 완전 교체해 도구 가이드 등이 날아가기 때문.
 */
class ClaudeCliSchemaDirectiveTest {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("deadline", Map.of("type", "string")));

    private List<Map<String, Object>> messages(String role, Object content) {
        List<Map<String, Object>> msgs = new ArrayList<>();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        msgs.add(m);
        return msgs;
    }

    @Test
    @DisplayName("schema 없으면 메시지 무변경")
    void noSchemaNoChange() {
        var msgs = messages("user", "원문 분석");
        ClaudeCliRunnerClient.appendSchemaDirectiveToLastUser(msgs, null);
        assertThat(msgs.get(0).get("content")).isEqualTo("원문 분석");
        ClaudeCliRunnerClient.appendSchemaDirectiveToLastUser(msgs, Map.of());
        assertThat(msgs.get(0).get("content")).isEqualTo("원문 분석");
    }

    @Test
    @DisplayName("String content 인 마지막 user 메시지 끝에 JSON 강제 지시 + schema 덧붙임")
    void appendsToStringContent() {
        var msgs = messages("user", "원문 분석");
        ClaudeCliRunnerClient.appendSchemaDirectiveToLastUser(msgs, SCHEMA);
        String content = (String) msgs.get(0).get("content");
        assertThat(content)
                .startsWith("원문 분석")
                .contains("ONLY a single valid JSON object")
                .contains("no markdown code fences")
                .contains("\"deadline\"");
    }

    @Test
    @DisplayName("멀티모달 content(배열)면 마지막 text 블록에 덧붙임")
    void appendsToTextBlockInMultimodal() {
        List<Map<String, Object>> blocks = new ArrayList<>();
        Map<String, Object> textBlock = new LinkedHashMap<>();
        textBlock.put("type", "text");
        textBlock.put("text", "이 PDF 분석");
        blocks.add(textBlock);
        var msgs = messages("user", blocks);

        ClaudeCliRunnerClient.appendSchemaDirectiveToLastUser(msgs, SCHEMA);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resultBlocks = (List<Map<String, Object>>) msgs.get(0).get("content");
        String text = (String) resultBlocks.get(0).get("text");
        assertThat(text).startsWith("이 PDF 분석").contains("ONLY a single valid JSON object");
    }

    @Test
    @DisplayName("user 메시지가 없으면(system 만) 무변경")
    void noUserMessageNoChange() {
        var msgs = messages("system", "system instruction");
        ClaudeCliRunnerClient.appendSchemaDirectiveToLastUser(msgs, SCHEMA);
        assertThat(msgs.get(0).get("content")).isEqualTo("system instruction");
    }

    @Test
    @DisplayName("불변 메시지 리스트(Map.of content) 에도 예외 없이 directive 부착 — 운영 회귀 방어")
    void worksOnImmutableMessageList() {
        // toRawMessages 비-멀티모달 경로가 Map.of(불변)로 만들던 케이스 재현.
        // List 자체는 가변(ArrayList)이되 content String 교체는 msg.put 이라 msg 가 가변이어야 함.
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(new LinkedHashMap<>(Map.of("role", "user", "content", "원문 분석")));

        // 예외 없이 동작해야 한다.
        ClaudeCliRunnerClient.appendSchemaDirectiveToLastUser(msgs, SCHEMA);
        assertThat((String) msgs.get(0).get("content")).contains("ONLY a single valid JSON object");
    }

    @Test
    @DisplayName("멀티모달 text 블록이 불변(Map.of)이어도 새 Map 으로 교체해 directive 부착 — 운영 회귀 방어")
    void worksOnImmutableMultimodalTextBlock() {
        // toAnthropicBlock 이 Map.of(불변)로 만든 text 블록 재현.
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(Map.of("type", "text", "text", "이 PDF 분석"));  // 불변
        var msgs = messages("user", blocks);

        ClaudeCliRunnerClient.appendSchemaDirectiveToLastUser(msgs, SCHEMA);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resultBlocks = (List<Map<String, Object>>) msgs.get(0).get("content");
        assertThat((String) resultBlocks.get(0).get("text"))
                .startsWith("이 PDF 분석").contains("ONLY a single valid JSON object");
    }
}
