package com.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.evaluation.EvaluationService;
import com.platform.evaluation.MCPEvaluationClient;
import com.platform.mcp.MCPServerClient;
import com.platform.policy.MCPSafetyClient;
import com.platform.rag.MCPRagClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
 * MCP 클라이언트 통합 테스트.
 *
 * Sprint 18: MCP 호출 → 응답 파싱 → 비즈니스 로직 반영 검증.
 *
 * MCPServerClient를 Mock으로 대체하여, Spring Boot ↔ Python MCP Server 간
 * 전체 라운드트립(요청 직렬화 → MCP 호출 → 응답 역직렬화 → 비즈니스 로직)을 검증.
 */
@ExtendWith(MockitoExtension.class)
class MCPClientIntegrationTest {

    @Mock private MCPServerClient ragMcpClient;
    @Mock private MCPServerClient safetyMcpClient;
    @Mock private MCPServerClient evalMcpClient;
    @Mock private com.platform.tool.ToolRegistry toolRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─── RAG Pipeline 통합 ────────────────────────────────────────────

    @Nested
    class RagPipelineIntegration {

        private MCPRagClient ragClient;

        @BeforeEach
        void setUp() throws Exception {
            ragClient = new MCPRagClient(objectMapper, toolRegistry);
            injectMcpClient(ragClient, MCPRagClient.class, ragMcpClient);
        }

        @Test
        void ingest_parseAndStore_fullRoundTrip() {
            // given — Python MCP Server가 인제스션 성공 응답 반환
            String mcpResponse = """
                    {
                      "source_id": "kb-001",
                      "document_id": "doc-readme",
                      "chunks_created": 12,
                      "success": true,
                      "errors": []
                    }
                    """;
            when(ragMcpClient.callTool(eq("ingest_document"), any())).thenReturn(mcpResponse);

            // when — MCP 호출 → 응답 파싱
            Map<String, Object> result = ragClient.ingestDocument(
                    "kb-001", "# README\n긴 문서 내용...", "doc-readme",
                    "semantic", Map.of("chunk_size", 512), null);

            // then — 비즈니스 로직에서 사용할 필드 검증
            assertThat(result).containsEntry("success", true);
            assertThat(result).containsEntry("source_id", "kb-001");
            assertThat(((Number) result.get("chunks_created")).intValue()).isEqualTo(12);
            assertThat((List<?>) result.get("errors")).isEmpty();
        }

        @Test
        void hybridSearch_thenRerank_fullRoundTrip() {
            // given — 하이브리드 검색 응답
            String searchResponse = """
                    {
                      "query": "Spring Boot 설정 방법",
                      "results": [
                        {"content": "application.yml 파일에서 설정", "metadata": {"source": "kb-001"}, "combined_score": 0.85},
                        {"content": "Spring Boot Auto Configuration", "metadata": {"source": "kb-001"}, "combined_score": 0.72},
                        {"content": "관련 없는 문서", "metadata": {"source": "kb-002"}, "combined_score": 0.45}
                      ]
                    }
                    """;
            when(ragMcpClient.callTool(eq("search_hybrid"), any())).thenReturn(searchResponse);

            // 리랭킹 응답
            String rerankResponse = """
                    {
                      "results": [
                        {"content": "application.yml 파일에서 설정", "metadata": {"source": "kb-001"}, "rerank_score": 0.95},
                        {"content": "Spring Boot Auto Configuration", "metadata": {"source": "kb-001"}, "rerank_score": 0.78}
                      ]
                    }
                    """;
            when(ragMcpClient.callTool(eq("rerank_results"), any())).thenReturn(rerankResponse);

            // when — 검색 + 리랭킹 연쇄 호출
            Map<String, Object> searchResult = ragClient.searchHybrid(
                    "Spring Boot 설정 방법", "kb-001", 5, 0.7, 0.3);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) searchResult.get("results");
            assertThat(candidates).hasSize(3);

            Map<String, Object> rerankResult = ragClient.rerankResults("Spring Boot 설정 방법", candidates, 2);

            // then — 리랭킹 후 상위 2개만 반환, 점수 순서 검증
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> reranked = (List<Map<String, Object>>) rerankResult.get("results");
            assertThat(reranked).hasSize(2);
            assertThat(((Number) reranked.get(0).get("rerank_score")).doubleValue()).isGreaterThan(
                    ((Number) reranked.get(1).get("rerank_score")).doubleValue());
        }

        @Test
        void ingest_withErrors_shouldPropagateErrorList() {
            // given — 부분 실패 응답
            String mcpResponse = """
                    {
                      "source_id": "kb-001",
                      "document_id": "doc-broken",
                      "chunks_created": 0,
                      "success": false,
                      "errors": [{"error": "Unsupported file format", "detail": "Cannot parse .xyz"}]
                    }
                    """;
            when(ragMcpClient.callTool(eq("ingest_document"), any())).thenReturn(mcpResponse);

            // when
            Map<String, Object> result = ragClient.ingestDocument(
                    "kb-001", "binary content", "doc-broken", "fixed", null, null);

            // then
            assertThat(result).containsEntry("success", false);
            assertThat(((Number) result.get("chunks_created")).intValue()).isZero();
            assertThat((List<?>) result.get("errors")).hasSize(1);
        }
    }

    // ─── Safety 통합 ─────────────────────────────────────────────────

    @Nested
    class SafetyIntegration {

        private MCPSafetyClient safetyClient;

        @BeforeEach
        void setUp() throws Exception {
            safetyClient = new MCPSafetyClient(objectMapper);
            injectMcpClient(safetyClient, MCPSafetyClient.class, safetyMcpClient);
        }

        @Test
        void detectAndMask_koreanPii_fullRoundTrip() {
            // given — PII 탐지 응답
            String detectResponse = """
                    {
                      "detections": [
                        {"entity_type": "PHONE_NUMBER", "start": 4, "end": 17, "score": 0.95, "text": "010-1234-5678"},
                        {"entity_type": "KR_RRN", "start": 23, "end": 37, "score": 0.99, "text": "900101-1234567"}
                      ],
                      "count": 2
                    }
                    """;
            when(safetyMcpClient.callTool(eq("detect_pii"), any())).thenReturn(detectResponse);

            // PII 마스킹 응답
            String maskResponse = """
                    {
                      "masked_text": "전화: ***-****-**** 주민번호: ******-*******",
                      "detections": [
                        {"entity_type": "PHONE_NUMBER", "text": "010-1234-5678"},
                        {"entity_type": "KR_RRN", "text": "900101-1234567"}
                      ],
                      "pii_found": true
                    }
                    """;
            when(safetyMcpClient.callTool(eq("mask_pii"), any())).thenReturn(maskResponse);

            String input = "전화: 010-1234-5678 주민번호: 900101-1234567";

            // when — 탐지 → 마스킹 연쇄
            Map<String, Object> detectResult = safetyClient.detectPii(input);
            assertThat(detectResult).containsEntry("count", 2);

            Map<String, Object> maskResult = safetyClient.maskPii(input);

            // then — 마스킹 결과 검증
            assertThat(maskResult).containsEntry("pii_found", true);
            String maskedText = (String) maskResult.get("masked_text");
            assertThat(maskedText).doesNotContain("010-1234-5678");
            assertThat(maskedText).doesNotContain("900101-1234567");
        }

        @Test
        void validateOutput_safe_fullRoundTrip() {
            // given
            String response = """
                    {"safe": true, "violations": [], "violation_count": 0}
                    """;
            when(safetyMcpClient.callTool(eq("validate_output"), any())).thenReturn(response);

            // when
            Map<String, Object> result = safetyClient.validateOutput("안전한 응답입니다");

            // then
            assertThat(result).containsEntry("safe", true);
            assertThat(result).containsEntry("violation_count", 0);
        }

        @Test
        void validateOutput_unsafe_shouldReturnViolations() {
            // given
            String response = """
                    {
                      "safe": false,
                      "violations": [
                        {"type": "pii_leak", "text": "010-1234-5678", "severity": "high"},
                        {"type": "harmful_content", "text": "위험한 내용", "severity": "medium"}
                      ],
                      "violation_count": 2
                    }
                    """;
            when(safetyMcpClient.callTool(eq("validate_output"), any())).thenReturn(response);

            // when
            Map<String, Object> result = safetyClient.validateOutput("위험한 응답");

            // then
            assertThat(result).containsEntry("safe", false);
            assertThat(((Number) result.get("violation_count")).intValue()).isEqualTo(2);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> violations = (List<Map<String, Object>>) result.get("violations");
            assertThat(violations).extracting(v -> v.get("type"))
                    .containsExactly("pii_leak", "harmful_content");
        }
    }

    // ─── Evaluation 통합 ─────────────────────────────────────────────

    @Nested
    class EvaluationIntegration {

        private MCPEvaluationClient evalClient;
        private EvaluationService evaluationService;

        @BeforeEach
        void setUp() throws Exception {
            evalClient = new MCPEvaluationClient(objectMapper);
            injectMcpClient(evalClient, MCPEvaluationClient.class, evalMcpClient);
            evaluationService = new EvaluationService(evalClient);
        }

        @Test
        void evaluateRag_viaService_fullRoundTrip() {
            // given — RAGAS 평가 응답
            String response = """
                    {
                      "faithfulness": 0.85,
                      "relevancy": 0.92,
                      "context_precision": 0.78,
                      "context_recall": 0.90
                    }
                    """;
            when(evalMcpClient.callTool(eq("evaluate_rag"), any())).thenReturn(response);

            // when — Service → Client → MCP → 응답 파싱
            Map<String, Object> result = evaluationService.evaluateRag(
                    "Python이란?",
                    "Python은 고수준 프로그래밍 언어입니다",
                    List.of("Python은 1991년에 만들어진 프로그래밍 언어입니다"),
                    "프로그래밍 언어");

            // then — 4개 메트릭 모두 0~1 범위
            assertThat(result).containsKeys("faithfulness", "relevancy", "context_precision", "context_recall");
            assertThat(((Number) result.get("faithfulness")).doubleValue()).isBetween(0.0, 1.0);
            assertThat(((Number) result.get("relevancy")).doubleValue()).isBetween(0.0, 1.0);
            assertThat(((Number) result.get("context_precision")).doubleValue()).isBetween(0.0, 1.0);
            assertThat(((Number) result.get("context_recall")).doubleValue()).isBetween(0.0, 1.0);
        }

        @Test
        void evaluateLlmOutput_viaService_fullRoundTrip() {
            // given — DeepEval 평가 응답
            String response = """
                    {
                      "hallucination_score": 0.1,
                      "toxicity_score": 0.0,
                      "bias_score": 0.05,
                      "details": {"hallucination": {"passed": true}, "toxicity": {"passed": true}}
                    }
                    """;
            when(evalMcpClient.callTool(eq("evaluate_llm_output"), any())).thenReturn(response);

            // when
            Map<String, Object> result = evaluationService.evaluateLlmOutput(
                    "서울의 인구는?",
                    "서울의 인구는 약 950만명입니다",
                    "서울특별시 인구: 9,428,372명 (2024년)",
                    List.of("hallucination", "toxicity", "bias"));

            // then — 낮은 점수 = 안전
            assertThat(((Number) result.get("hallucination_score")).doubleValue()).isLessThan(0.5);
            assertThat(((Number) result.get("toxicity_score")).doubleValue()).isLessThan(0.5);
            assertThat(((Number) result.get("bias_score")).doubleValue()).isLessThan(0.5);
        }

        @Test
        void comparePrompts_viaService_fullRoundTrip() {
            // given — 프롬프트 A/B 비교 응답
            String response = """
                    {
                      "summary": {
                        "prompt_a_avg": 0.72,
                        "prompt_b_avg": 0.88,
                        "improvement_pct": 22.22
                      },
                      "details": [
                        {"input": "Python이란?", "score_a": 0.70, "score_b": 0.85},
                        {"input": "Java 장점", "score_a": 0.74, "score_b": 0.91}
                      ]
                    }
                    """;
            when(evalMcpClient.callTool(eq("compare_prompts"), any())).thenReturn(response);

            // when
            Map<String, Object> result = evaluationService.comparePrompts(
                    List.of(
                            Map.of("input", "Python이란?", "expected_output", "프로그래밍 언어"),
                            Map.of("input", "Java 장점", "expected_output", "플랫폼 독립성")),
                    Map.of("id", "prompt-1", "version", "v1", "template", "답변하세요: {input}"),
                    Map.of("id", "prompt-1", "version", "v2", "template", "전문가로서 상세히 답변하세요: {input}"),
                    List.of("relevancy"));

            // then — prompt_b가 더 높은 점수
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) result.get("summary");
            double promptAAvg = ((Number) summary.get("prompt_a_avg")).doubleValue();
            double promptBAvg = ((Number) summary.get("prompt_b_avg")).doubleValue();
            assertThat(promptBAvg).isGreaterThan(promptAAvg);
            assertThat(((Number) summary.get("improvement_pct")).doubleValue()).isPositive();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> details = (List<Map<String, Object>>) result.get("details");
            assertThat(details).hasSize(2);
        }

        @Test
        void evaluateRag_whenMcpUnavailable_shouldThrowException() {
            // given — MCP 비활성화
            try {
                Field enabledField = MCPEvaluationClient.class.getDeclaredField("mcpEnabled");
                enabledField.setAccessible(true);
                enabledField.set(evalClient, false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            // when & then
            assertThatThrownBy(() -> evaluationService.evaluateRag("q", "a", List.of("c"), null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not available");
        }
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────

    private <T> void injectMcpClient(T target, Class<T> clazz, MCPServerClient mockClient) throws Exception {
        Field mcpClientField = clazz.getDeclaredField("mcpClient");
        mcpClientField.setAccessible(true);
        mcpClientField.set(target, mockClient);

        Field connectedField = clazz.getDeclaredField("connected");
        connectedField.setAccessible(true);
        connectedField.set(target, true);

        Field enabledField = clazz.getDeclaredField("mcpEnabled");
        enabledField.setAccessible(true);
        enabledField.set(target, true);
    }
}
