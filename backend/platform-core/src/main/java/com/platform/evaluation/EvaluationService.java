package com.platform.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 평가 비즈니스 로직 서비스.
 *
 * MCPEvaluationClient를 통해 Python Evaluation MCP Server에 위임.
 * MCP 미연결 시 예외를 던져 호출자가 핸들링하도록 한다.
 *
 * Sprint 18 (PY-009~PY-011): MCP 클라이언트 통합.
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    private final MCPEvaluationClient mcpEvaluationClient;

    public EvaluationService(MCPEvaluationClient mcpEvaluationClient) {
        this.mcpEvaluationClient = mcpEvaluationClient;
    }

    /**
     * RAG 품질 평가 (PY-006).
     *
     * @param question   사용자 질문
     * @param answer     RAG 생성 답변
     * @param contexts   참조 컨텍스트 목록
     * @param groundTruth 정답 (nullable)
     * @return {faithfulness, relevancy, context_precision, context_recall}
     */
    public Map<String, Object> evaluateRag(String question, String answer,
                                           List<String> contexts, String groundTruth) {
        ensureAvailable();
        log.info("Evaluating RAG quality: question='{}', contexts={}", truncate(question), contexts.size());

        Map<String, Object> result = mcpEvaluationClient.evaluateRag(question, answer, contexts, groundTruth);
        log.info("RAG evaluation result: faithfulness={}, relevancy={}",
                result.get("faithfulness"), result.get("relevancy"));
        return result;
    }

    /**
     * LLM 출력 평가 (PY-007).
     *
     * @param inputText  사용자 입력
     * @param outputText LLM 출력
     * @param context    참조 컨텍스트 (nullable)
     * @param metrics    평가 메트릭 목록 (nullable)
     * @return {hallucination_score, toxicity_score, bias_score, details}
     */
    public Map<String, Object> evaluateLlmOutput(String inputText, String outputText,
                                                  String context, List<String> metrics) {
        ensureAvailable();
        log.info("Evaluating LLM output: input='{}', metrics={}", truncate(inputText),
                metrics != null ? metrics : "all");

        Map<String, Object> result = mcpEvaluationClient.evaluateLlmOutput(
                inputText, outputText, context, metrics);
        log.info("LLM evaluation result: hallucination={}, toxicity={}, bias={}",
                result.get("hallucination_score"), result.get("toxicity_score"), result.get("bias_score"));
        return result;
    }

    /**
     * 프롬프트 회귀 테스트 (PY-008).
     *
     * @param testCases 테스트 케이스 목록 [{input, expected_output}]
     * @param promptA   기준 프롬프트 {id, version, template}
     * @param promptB   비교 프롬프트 {id, version, template}
     * @param metrics   비교 메트릭 (nullable)
     * @return {summary: {prompt_a_avg, prompt_b_avg, improvement_pct}, details: [...]}
     */
    public Map<String, Object> comparePrompts(List<Map<String, String>> testCases,
                                               Map<String, String> promptA,
                                               Map<String, String> promptB,
                                               List<String> metrics) {
        ensureAvailable();
        log.info("Comparing prompts: promptA={}, promptB={}, testCases={}",
                promptA.get("id"), promptB.get("id"), testCases.size());

        Map<String, Object> result = mcpEvaluationClient.comparePrompts(
                testCases, promptA, promptB, metrics);
        log.info("Prompt comparison result: summary={}", result.get("summary"));
        return result;
    }

    /**
     * Evaluation MCP Server 가용 여부.
     */
    public boolean isAvailable() {
        return mcpEvaluationClient.isAvailable();
    }

    private void ensureAvailable() {
        if (!mcpEvaluationClient.isAvailable()) {
            throw new IllegalStateException(
                    "Evaluation MCP Server is not available. Enable it via evaluation.mcp.enabled=true");
        }
    }

    private String truncate(String text) {
        return text != null && text.length() > 50 ? text.substring(0, 50) + "..." : text;
    }
}
