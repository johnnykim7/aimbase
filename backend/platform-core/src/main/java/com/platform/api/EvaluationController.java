package com.platform.api;

import com.platform.domain.RagEvaluationEntity;
import com.platform.evaluation.EvaluationService;
import com.platform.rag.MCPRagClient;
import com.platform.repository.RagEvaluationRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
    private final MCPRagClient mcpRagClient;
    private final RagEvaluationRepository ragEvaluationRepository;

    public EvaluationController(EvaluationService evaluationService,
                                 MCPRagClient mcpRagClient,
                                 RagEvaluationRepository ragEvaluationRepository) {
        this.evaluationService = evaluationService;
        this.mcpRagClient = mcpRagClient;
        this.ragEvaluationRepository = ragEvaluationRepository;
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

    /**
     * POST /api/v1/evaluations/rag-quality — RAGAS 배치 평가 실행 (PRD-120, PY-026).
     * 비동기 실행 후 evaluation ID 반환.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/rag-quality")
    public ResponseEntity<ApiResponse<Map<String, Object>>> evaluateRagQuality(
            @RequestBody RagQualityRequest request) {

        String mode = request.mode() != null && !request.mode().isBlank() ? request.mode() : "fast";

        RagEvaluationEntity entity = new RagEvaluationEntity();
        entity.setSourceId(request.sourceId());
        entity.setConfig(request.config() != null ? request.config() : Map.of());
        entity.setTestSet(request.testSet());
        entity.setMode(mode);
        entity.setStatus("running");
        ragEvaluationRepository.save(entity);

        UUID evalId = entity.getId();
        String tenantId = com.platform.tenant.TenantContext.getTenantId();

        CompletableFuture.runAsync(() -> {
            if (tenantId != null) com.platform.tenant.TenantContext.setTenantId(tenantId);
            try {
                Map<String, Object> result = mcpRagClient.evaluateRag(
                        request.sourceId(), request.testSet(), request.config(), mode);

                RagEvaluationEntity e = ragEvaluationRepository.findById(evalId).orElseThrow();
                if (Boolean.TRUE.equals(result.get("success"))) {
                    e.setMetrics(result.get("metrics") instanceof Map
                            ? (Map<String, Object>) result.get("metrics") : Map.of());
                    e.setSampleCount(result.get("sample_count") instanceof Number n ? n.intValue() : 0);
                    e.setStatus("completed");
                } else {
                    e.setStatus("failed");
                    e.setErrorMessage((String) result.getOrDefault("error", "Unknown error"));
                }
                e.setCompletedAt(OffsetDateTime.now());
                ragEvaluationRepository.save(e);
            } catch (Exception ex) {
                ragEvaluationRepository.findById(evalId).ifPresent(e -> {
                    e.setStatus("failed");
                    e.setErrorMessage(ex.getMessage());
                    e.setCompletedAt(OffsetDateTime.now());
                    ragEvaluationRepository.save(e);
                });
            } finally {
                com.platform.tenant.TenantContext.clear();
            }
        });

        return ResponseEntity.accepted().body(
                ApiResponse.ok(Map.of("evaluation_id", evalId.toString(), "status", "running")));
    }

    /**
     * GET /api/v1/evaluations/rag-quality/{id} — 평가 결과 조회.
     */
    @GetMapping("/rag-quality/{id}")
    public ResponseEntity<ApiResponse<RagEvaluationEntity>> getRagQualityResult(
            @PathVariable UUID id) {
        return ragEvaluationRepository.findById(id)
                .map(e -> ResponseEntity.ok(ApiResponse.ok(e)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/v1/evaluations/rag-quality?source_id=xxx — 소스별 평가 이력 조회.
     */
    @GetMapping("/rag-quality")
    public ResponseEntity<ApiResponse<List<RagEvaluationEntity>>> listRagQualityResults(
            @RequestParam("source_id") String sourceId) {
        List<RagEvaluationEntity> results = ragEvaluationRepository
                .findBySourceIdOrderByCreatedAtDesc(sourceId);
        return ResponseEntity.ok(ApiResponse.ok(results));
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

    public record RagQualityRequest(
            String sourceId,
            List<Map<String, Object>> testSet,
            Map<String, Object> config,
            String mode
    ) {}
}
