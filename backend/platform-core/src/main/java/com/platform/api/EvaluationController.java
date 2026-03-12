package com.platform.api;

import com.platform.evaluation.EvaluationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 평가 API 컨트롤러.
 *
 * RAG 품질 평가, LLM 출력 평가, 프롬프트 회귀 테스트 엔드포인트 제공.
 *
 * Sprint 18 (PY-009~PY-011): MCP 클라이언트 통합.
 */
@RestController
@RequestMapping("/api/v1/evaluations")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    /**
     * GET /api/v1/evaluations/status — Evaluation MCP 서버 가용 상태 확인.
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        boolean available = evaluationService.isAvailable();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("available", available)));
    }

    /**
     * POST /api/v1/evaluations/rag — RAG 품질 평가 (PY-006).
     *
     * Request Body:
     * {
     *   "question": "Python이란?",
     *   "answer": "Python은 프로그래밍 언어입니다",
     *   "contexts": ["Python은 고수준 프로그래밍 언어입니다"],
     *   "ground_truth": "프로그래밍 언어" (optional)
     * }
     */
    @PostMapping("/rag")
    public ResponseEntity<ApiResponse<Map<String, Object>>> evaluateRag(
            @RequestBody RagEvaluationRequest request) {
        Map<String, Object> result = evaluationService.evaluateRag(
                request.question(), request.answer(),
                request.contexts(), request.groundTruth());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * POST /api/v1/evaluations/llm-output — LLM 출력 평가 (PY-007).
     *
     * Request Body:
     * {
     *   "input_text": "질문입니다",
     *   "output_text": "답변입니다",
     *   "context": "컨텍스트" (optional),
     *   "metrics": ["hallucination", "toxicity"] (optional)
     * }
     */
    @PostMapping("/llm-output")
    public ResponseEntity<ApiResponse<Map<String, Object>>> evaluateLlmOutput(
            @RequestBody LlmOutputEvaluationRequest request) {
        Map<String, Object> result = evaluationService.evaluateLlmOutput(
                request.inputText(), request.outputText(),
                request.context(), request.metrics());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * POST /api/v1/evaluations/prompt-comparison — 프롬프트 회귀 테스트 (PY-008).
     *
     * Request Body:
     * {
     *   "test_cases": [{"input": "q1", "expected_output": "a1"}],
     *   "prompt_a": {"id": "p1", "version": "v1", "template": "{input}"},
     *   "prompt_b": {"id": "p1", "version": "v2", "template": "답변: {input}"},
     *   "metrics": ["relevancy"] (optional)
     * }
     */
    @PostMapping("/prompt-comparison")
    public ResponseEntity<ApiResponse<Map<String, Object>>> comparePrompts(
            @RequestBody PromptComparisonRequest request) {
        Map<String, Object> result = evaluationService.comparePrompts(
                request.testCases(), request.promptA(),
                request.promptB(), request.metrics());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── 요청 DTO ────────────────────────────────────────────────────

    public record RagEvaluationRequest(
            String question,
            String answer,
            List<String> contexts,
            String groundTruth
    ) {}

    public record LlmOutputEvaluationRequest(
            String inputText,
            String outputText,
            String context,
            List<String> metrics
    ) {}

    public record PromptComparisonRequest(
            List<Map<String, String>> testCases,
            Map<String, String> promptA,
            Map<String, String> promptB,
            List<String> metrics
    ) {}
}
