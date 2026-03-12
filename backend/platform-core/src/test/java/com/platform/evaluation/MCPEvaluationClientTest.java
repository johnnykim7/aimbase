package com.platform.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.mcp.MCPServerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * MCPEvaluationClient 단위 테스트.
 *
 * Sprint 17 (PY-006~PY-008): Evaluation MCP 클라이언트.
 */
@ExtendWith(MockitoExtension.class)
class MCPEvaluationClientTest {

    @Mock private MCPServerClient mcpServerClient;

    private MCPEvaluationClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        client = new MCPEvaluationClient(objectMapper);

        // mcpClient 주입 (리플렉션)
        Field mcpClientField = MCPEvaluationClient.class.getDeclaredField("mcpClient");
        mcpClientField.setAccessible(true);
        mcpClientField.set(client, mcpServerClient);

        Field connectedField = MCPEvaluationClient.class.getDeclaredField("connected");
        connectedField.setAccessible(true);
        connectedField.set(client, true);

        Field enabledField = MCPEvaluationClient.class.getDeclaredField("mcpEnabled");
        enabledField.setAccessible(true);
        enabledField.set(client, true);
    }

    @Test
    void evaluateRag_shouldCallToolAndParseResult() {
        // given
        String response = """
                {"faithfulness": 0.85, "relevancy": 0.92, "context_precision": 0.78, "context_recall": 0.90}
                """;
        when(mcpServerClient.callTool(eq("evaluate_rag"), any())).thenReturn(response);

        // when
        Map<String, Object> result = client.evaluateRag(
                "Python이란?",
                "Python은 프로그래밍 언어입니다",
                List.of("Python은 고수준 프로그래밍 언어입니다"),
                "프로그래밍 언어"
        );

        // then
        assertThat(result).containsKeys("faithfulness", "relevancy", "context_precision", "context_recall");
        assertThat((Number) result.get("faithfulness")).satisfies(n ->
                assertThat(n.doubleValue()).isBetween(0.0, 1.0));
        verify(mcpServerClient).callTool(eq("evaluate_rag"), any());
    }

    @Test
    void evaluateLlmOutput_shouldCallToolAndParseResult() {
        // given
        String response = """
                {"hallucination_score": 0.1, "toxicity_score": 0.0, "bias_score": 0.05, "details": {}}
                """;
        when(mcpServerClient.callTool(eq("evaluate_llm_output"), any())).thenReturn(response);

        // when
        Map<String, Object> result = client.evaluateLlmOutput(
                "질문입니다",
                "답변입니다",
                "컨텍스트",
                List.of("hallucination", "toxicity")
        );

        // then
        assertThat(result).containsKeys("hallucination_score", "toxicity_score", "bias_score");
        verify(mcpServerClient).callTool(eq("evaluate_llm_output"), any());
    }

    @Test
    void comparePrompts_shouldCallToolAndParseResult() {
        // given
        String response = """
                {
                  "summary": {"prompt_a_avg": 0.75, "prompt_b_avg": 0.85, "improvement_pct": 13.33},
                  "details": [{"input": "q1", "score_a": 0.75, "score_b": 0.85}]
                }
                """;
        when(mcpServerClient.callTool(eq("compare_prompts"), any())).thenReturn(response);

        // when
        Map<String, Object> result = client.comparePrompts(
                List.of(Map.of("input", "q1", "expected_output", "a1")),
                Map.of("id", "p1", "version", "v1", "template", "{input}"),
                Map.of("id", "p1", "version", "v2", "template", "답변: {input}"),
                List.of("relevancy")
        );

        // then
        assertThat(result).containsKeys("summary", "details");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertThat(summary).containsKeys("prompt_a_avg", "prompt_b_avg", "improvement_pct");
        verify(mcpServerClient).callTool(eq("compare_prompts"), any());
    }

    @Test
    void isAvailable_whenEnabledAndConnected_returnsTrue() {
        assertThat(client.isAvailable()).isTrue();
    }

    @Test
    void evaluateRag_whenMcpFails_shouldThrowException() {
        // given
        when(mcpServerClient.callTool(eq("evaluate_rag"), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        // when & then
        assertThatThrownBy(() -> client.evaluateRag("q", "a", List.of("c"), null))
                .isInstanceOf(RuntimeException.class);
    }
}
