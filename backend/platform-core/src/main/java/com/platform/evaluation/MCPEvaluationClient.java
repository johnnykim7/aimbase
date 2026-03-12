package com.platform.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.mcp.MCPServerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;

/**
 * Python Evaluation MCP Server 호출 클라이언트.
 *
 * RAGAS 기반 RAG 품질 평가 + DeepEval 기반 LLM 출력 평가를
 * Python 사이드카에 위임.
 *
 * Sprint 17 (PY-006, PY-007, PY-008): Evaluation.
 */
@Component
public class MCPEvaluationClient {

    private static final Logger log = LoggerFactory.getLogger(MCPEvaluationClient.class);

    private final ObjectMapper objectMapper;

    @Value("${evaluation.mcp.url:http://localhost:8002}")
    private String mcpServerUrl;

    @Value("${evaluation.mcp.enabled:false}")
    private boolean mcpEnabled;

    private MCPServerClient mcpClient;
    private boolean connected = false;

    public MCPEvaluationClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (mcpEnabled) {
            try {
                mcpClient = new MCPServerClient("evaluation", "sse", Map.of("url", mcpServerUrl));
                mcpClient.connect();
                connected = true;
                log.info("Connected to Evaluation MCP Server at {}", mcpServerUrl);
            } catch (Exception e) {
                log.warn("Failed to connect to Evaluation MCP Server at {}: {}.",
                        mcpServerUrl, e.getMessage());
                connected = false;
            }
        }
    }

    @PreDestroy
    public void destroy() {
        if (mcpClient != null) {
            mcpClient.close();
        }
    }

    public boolean isAvailable() {
        return mcpEnabled && connected;
    }

    /**
     * RAG 품질 평가 — evaluate_rag 도구 호출 (PY-006).
     *
     * @param question 사용자 질문
     * @param answer RAG 생성 답변
     * @param contexts 참조 컨텍스트 목록
     * @param groundTruth 정답 (optional)
     * @return {faithfulness, relevancy, context_precision, context_recall}
     */
    public Map<String, Object> evaluateRag(String question, String answer,
                                           List<String> contexts, String groundTruth) {
        try {
            Map<String, Object> input = Map.of(
                    "question", question,
                    "answer", answer,
                    "contexts", objectMapper.writeValueAsString(contexts),
                    "ground_truth", groundTruth != null ? groundTruth : ""
            );
            String result = mcpClient.callTool("evaluate_rag", input);
            return parseJson(result);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize contexts", e);
        }
    }

    /**
     * LLM 출력 평가 — evaluate_llm_output 도구 호출 (PY-007).
     *
     * @param inputText 사용자 입력
     * @param outputText LLM 출력
     * @param context 참조 컨텍스트 (optional)
     * @param metrics 평가 메트릭 목록 (optional)
     * @return {hallucination_score, toxicity_score, bias_score, details}
     */
    public Map<String, Object> evaluateLlmOutput(String inputText, String outputText,
                                                  String context, List<String> metrics) {
        try {
            Map<String, Object> input = Map.of(
                    "input_text", inputText,
                    "output_text", outputText,
                    "context", context != null ? context : "",
                    "metrics", metrics != null ? objectMapper.writeValueAsString(metrics) : "[]"
            );
            String result = mcpClient.callTool("evaluate_llm_output", input);
            return parseJson(result);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize metrics", e);
        }
    }

    /**
     * 프롬프트 회귀 테스트 — compare_prompts 도구 호출 (PY-008).
     *
     * @param testCases 테스트 케이스 목록 [{input, expected_output}]
     * @param promptA 기준 프롬프트 {id, version, template}
     * @param promptB 비교 프롬프트 {id, version, template}
     * @param metrics 비교 메트릭 (optional)
     * @return {summary: {prompt_a_avg, prompt_b_avg, improvement_pct}, details: [...]}
     */
    public Map<String, Object> comparePrompts(List<Map<String, String>> testCases,
                                               Map<String, String> promptA,
                                               Map<String, String> promptB,
                                               List<String> metrics) {
        try {
            Map<String, Object> input = Map.of(
                    "test_cases", objectMapper.writeValueAsString(testCases),
                    "prompt_a", objectMapper.writeValueAsString(promptA),
                    "prompt_b", objectMapper.writeValueAsString(promptB),
                    "metrics", metrics != null ? objectMapper.writeValueAsString(metrics) : "[]"
            );
            String result = mcpClient.callTool("compare_prompts", input);
            return parseJson(result);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize prompt comparison input", e);
        }
    }

    private Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse Evaluation MCP response: " + json, e);
        }
    }
}
