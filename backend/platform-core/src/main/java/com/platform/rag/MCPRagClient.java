package com.platform.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.mcp.MCPServerClient;
import com.platform.mcp.MCPToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.ToolRegistry;

import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;

/**
 * Python RAG Pipeline MCP Server 호출 클라이언트.
 *
 * Spring MCP Client → Python FastMCP Server (JSON-RPC over SSE) 방식으로
 * 기존 Java IngestionPipeline/VectorSearcher 기능을 Python 사이드카에 위임.
 *
 * v2.0 (CR-002): AI 특화 기능을 Python MCP Server로 전환.
 * v3.7 (CR-019): 연결 시 MCP 도구를 ToolRegistry에 자동 등록.
 */
@Component
public class MCPRagClient {

    private static final Logger log = LoggerFactory.getLogger(MCPRagClient.class);

    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;

    @Value("${rag.mcp.url:http://localhost:8002}")
    private String mcpServerUrl;

    @Value("${rag.mcp.enabled:false}")
    private boolean mcpEnabled;

    // 사이드카 호출 타임아웃(초). parse_document 의 PDF 다운로드+파싱이 30초를 넘기는 사례(대용량 PDF)로
    // 기본 30초가 부족 → 180초로 상향. 환경변수 RAG_MCP_REQUEST_TIMEOUT_SECONDS 로 조정 가능.
    @Value("${rag.mcp.request-timeout-seconds:180}")
    private int requestTimeoutSeconds;

    private MCPServerClient mcpClient;
    private boolean connected = false;

    public MCPRagClient(ObjectMapper objectMapper, @Lazy ToolRegistry toolRegistry) {
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void init() {
        if (mcpEnabled) {
            try {
                mcpClient = new MCPServerClient("rag-pipeline", "sse", Map.of("url", mcpServerUrl), requestTimeoutSeconds);
                mcpClient.connect();
                connected = true;
                log.info("Connected to RAG Pipeline MCP Server at {} (requestTimeout={}s)", mcpServerUrl, requestTimeoutSeconds);
                registerToolsInRegistry();
            } catch (Exception e) {
                log.warn("Failed to connect to RAG Pipeline MCP Server at {}: {}. Falling back to Java implementation.",
                        mcpServerUrl, e.getMessage());
                connected = false;
            }
        }
    }

    /**
     * 사이드카의 MCP 도구를 ToolRegistry에 등록하여 워크플로우/채팅에서 사용 가능하게 함.
     */
    private void registerToolsInRegistry() {
        try {
            List<UnifiedToolDef> tools = mcpClient.discoverTools();
            for (UnifiedToolDef tool : tools) {
                toolRegistry.register(new MCPToolExecutor(mcpClient, tool));
            }
            log.info("Registered {} MCP tool(s) from RAG Pipeline sidecar in ToolRegistry", tools.size());
        } catch (Exception e) {
            log.warn("Failed to register sidecar tools in ToolRegistry: {}", e.getMessage());
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
     * 사이드카 SSE 세션 워밍업 핑.
     *
     * SDK 0.10.0 의 SSE 세션은 사이드카 재기동/유휴 시 무효화되는데, MCPServerClient 가
     * 죽은 session_id 로 POST 하면 사이드카는 404("Could not find session") 를 주지만 SDK 가
     * 이를 응답으로 전파하지 못해 다음 첫 tool call 이 requestTimeout 풀타임아웃(180초) 으로 hang 한다.
     * 가벼운 호출(list_document_formats, 인자 없음)을 주기적으로 보내 세션을 살아있게 유지 →
     * 첫 호출 cold-start hang 자체를 예방한다. 실패하면 callTool 내부 재연결 로직이 새 세션을 만든다.
     * (MCPServerManager.healthCheckAndReconnect 의 사이드카 버전)
     */
    @Scheduled(fixedDelayString = "${rag.mcp.healthcheck.interval-ms:60000}",
               initialDelayString = "${rag.mcp.healthcheck.initial-delay-ms:90000}")
    public void healthCheckPing() {
        if (!mcpEnabled || !connected || mcpClient == null) {
            return;
        }
        try {
            mcpClient.callTool("list_document_formats", Map.of());
        } catch (Exception e) {
            // callTool 내부에서 이미 1회 재연결을 시도한다. 여기까지 오면 사이드카 자체가 죽은 상태.
            log.warn("RAG sidecar health ping failed (session may be re-established on next call): {}", e.getMessage());
        }
    }

    /**
     * Python MCP Server의 ingest_document 도구 호출.
     *
     * @return {source_id, document_id, chunks_created, success, errors}
     */
    public Map<String, Object> ingestDocument(String sourceId, String content,
                                                String documentId, String chunkingStrategy,
                                                Map<String, Object> chunkingConfig,
                                                String embeddingModel) {
        Map<String, Object> input = Map.of(
                "source_id", sourceId,
                "content", content,
                "document_id", documentId != null ? documentId : "",
                "chunking_strategy", chunkingStrategy != null ? chunkingStrategy : "semantic",
                "chunking_config", toJsonString(chunkingConfig != null ? chunkingConfig : Map.of()),
                "embedding_model", embeddingModel != null ? embeddingModel : ""
        );

        String result = mcpClient.callTool("ingest_document", input);
        log.info("MCP ingest_document result: {}", result);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 ingest_file 도구 호출.
     * 파일 경로를 전달하면 사이드카가 파싱→청킹→임베딩→저장 전체를 처리.
     *
     * @return {source_id, document_id, chunks_created, success, errors}
     */
    public Map<String, Object> ingestFile(String sourceId, String filePath,
                                           String storageBasePath, String documentId,
                                           String chunkingStrategy, Map<String, Object> chunkingConfig) {
        return ingestFile(sourceId, filePath, storageBasePath, documentId, chunkingStrategy, chunkingConfig, null);
    }

    /**
     * Python MCP Server의 ingest_file 도구 호출 (임베딩 모델 지정).
     *
     * @param embeddingModel 임베딩에 사용할 모델 (null이면 사이드카 기본 모델)
     * @return {source_id, document_id, chunks_created, success, errors}
     */
    public Map<String, Object> ingestFile(String sourceId, String filePath,
                                           String storageBasePath, String documentId,
                                           String chunkingStrategy, Map<String, Object> chunkingConfig,
                                           String embeddingModel) {
        Map<String, Object> input = new java.util.HashMap<>();
        input.put("source_id", sourceId);
        input.put("file_path", filePath);
        input.put("storage_base_path", storageBasePath);
        input.put("document_id", documentId != null ? documentId : "");
        input.put("chunking_strategy", chunkingStrategy != null ? chunkingStrategy : "fixed");
        input.put("chunking_config", toJsonString(chunkingConfig != null ? chunkingConfig : Map.of()));
        input.put("embedding_model", embeddingModel != null ? embeddingModel : "");

        String result = mcpClient.callTool("ingest_file", input);
        log.info("MCP ingest_file result: {}", result);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 search_hybrid 도구 호출.
     *
     * @return {query, results: [{content, metadata, vector_score, keyword_score, combined_score}]}
     */
    public Map<String, Object> searchHybrid(String query, String sourceId, int topK,
                                              double vectorWeight, double keywordWeight) {
        return searchHybrid(query, sourceId, topK, vectorWeight, keywordWeight, null);
    }

    /**
     * Python MCP Server의 search_hybrid 도구 호출 (임베딩 모델 지정).
     *
     * @param embeddingModel 쿼리 임베딩에 사용할 모델 (null이면 사이드카 기본 모델)
     * @return {query, results: [{content, metadata, vector_score, keyword_score, combined_score}]}
     */
    public Map<String, Object> searchHybrid(String query, String sourceId, int topK,
                                              double vectorWeight, double keywordWeight,
                                              String embeddingModel) {
        Map<String, Object> input = new java.util.HashMap<>();
        input.put("query", query);
        input.put("source_id", sourceId);
        input.put("top_k", topK);
        input.put("vector_weight", vectorWeight);
        input.put("keyword_weight", keywordWeight);
        if (embeddingModel != null && !embeddingModel.isBlank()) {
            input.put("embedding_model", embeddingModel);
        }

        String result = mcpClient.callTool("search_hybrid", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 rerank_results 도구 호출.
     *
     * @return {results: [{content, metadata, rerank_score}]}
     */
    public Map<String, Object> rerankResults(String query, List<Map<String, Object>> documents,
                                               int topK) {
        Map<String, Object> input = Map.of(
                "query", query,
                "documents", toJsonString(documents),
                "top_k", topK
        );

        String result = mcpClient.callTool("rerank_results", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 chunk_document 도구 호출.
     */
    public Map<String, Object> chunkDocument(String content, String strategy,
                                               Map<String, Object> config) {
        Map<String, Object> input = Map.of(
                "content", content,
                "strategy", strategy != null ? strategy : "semantic",
                "config", toJsonString(config != null ? config : Map.of())
        );

        String result = mcpClient.callTool("chunk_document", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 transform_query 도구 호출 (PY-005).
     *
     * @param query 원본 사용자 쿼리
     * @param strategy "hyde", "multi_query", "step_back"
     * @return {original_query, transformed_queries: [...], strategy_used, metadata}
     */
    public Map<String, Object> transformQuery(String query, String strategy) {
        Map<String, Object> input = Map.of(
                "query", query,
                "strategy", strategy != null ? strategy : "multi_query",
                "llm_config", "{}"
        );

        String result = mcpClient.callTool("transform_query", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 finetune_embeddings 도구 호출 (PY-012).
     *
     * @return {model_path, metrics: {loss, training_samples, ...}, success}
     */
    public Map<String, Object> finetuneEmbeddings(String baseModel,
                                                     List<Map<String, String>> trainingData,
                                                     int epochs, int batchSize) {
        Map<String, Object> input = Map.of(
                "base_model", baseModel != null ? baseModel : "",
                "training_data", toJsonString(trainingData),
                "epochs", epochs,
                "batch_size", batchSize
        );

        String result = mcpClient.callTool("finetune_embeddings", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 embed_texts 도구 호출.
     */
    public Map<String, Object> embedTexts(List<String> texts, String model) {
        Map<String, Object> input = Map.of(
                "texts", texts,
                "model", model != null ? model : ""
        );

        String result = mcpClient.callTool("embed_texts", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 parse_document 도구 호출.
     *
     * @return {content, metadata, file_type, success}
     */
    public Map<String, Object> parseDocument(String fileContent, String fileType) {
        Map<String, Object> input = Map.of(
                "file_content", fileContent,
                "file_type", fileType != null ? fileType : ""
        );

        String result = mcpClient.callTool("parse_document", input);
        return parseJson(result);
    }

    /**
     * parse_document 를 로컬 path 로 호출 (사이드카가 직접 읽음 — base64 왕복 제거).
     * LLM 이 준 file_path 를 BE 가 읽지 않고 그대로 사이드카에 패스한다.
     * 사이드카 PARSE_ALLOWED_ROOTS 화이트리스트 내 path 만 허용된다.
     *
     * @return {content, metadata, file_type, success}
     */
    public Map<String, Object> parseDocumentByPath(String filePath, String fileType) {
        Map<String, Object> input = Map.of(
                "file_path", filePath,
                "file_type", fileType != null ? fileType : ""
        );

        String result = mcpClient.callTool("parse_document", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 read_pdf 도구 호출 (CR-092 — OCR 옵션 포함).
     *
     * @param fileBase64    Base64 인코딩된 PDF
     * @param extractImages 이미지 추출 여부 (텍스트 추출 경로에서만)
     * @param ocrEnabled    OCR 사용 여부. true 면 Tesseract 경로
     * @param ocrLanguages  Tesseract 언어 코드 (BIZ-106 화이트리스트, 예: "kor+eng")
     * @param ocrMaxPages   OCR 페이지 상한 (BIZ-105)
     * @return {pages, page_count, success, ...}
     */
    public Map<String, Object> readPdf(String fileBase64, boolean extractImages,
                                       boolean ocrEnabled, String ocrLanguages, int ocrMaxPages) {
        Map<String, Object> input = Map.of(
                "file_base64", fileBase64,
                "extract_images", extractImages,
                "ocr_enabled", ocrEnabled,
                "ocr_languages", ocrLanguages != null ? ocrLanguages : "kor+eng",
                "ocr_max_pages", ocrMaxPages
        );

        String result = mcpClient.callTool("read_pdf", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 ocr_image 도구 호출 (CR-092).
     *
     * @param fileBase64 Base64 인코딩된 이미지 (JPG/PNG/etc.)
     * @param languages  Tesseract 언어 코드 (BIZ-106 화이트리스트, 예: "kor+eng")
     * @return {text, success, languages, ...} 또는 {success: false, error}
     */
    public Map<String, Object> ocrImage(String fileBase64, String languages) {
        Map<String, Object> input = Map.of(
                "file_base64", fileBase64,
                "languages", languages != null ? languages : "kor+eng"
        );

        String result = mcpClient.callTool("ocr_image", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 pdf_page_count 도구 호출 (CR-095 — 페이지 가드).
     *
     * 렌더 없이 페이지 수만 빠르게 센다 (openclaude getPDFPageCount/pdfinfo 대응).
     * 10페이지 분할 가드 판단에 쓴다.
     *
     * @param fileBase64 Base64 인코딩된 PDF
     * @return {success, page_count} 또는 {success:false, error}
     */
    public Map<String, Object> pdfPageCount(String fileBase64) {
        Map<String, Object> input = Map.of("file_base64", fileBase64);
        String result = mcpClient.callTool("pdf_page_count", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 pdf_to_images 도구 호출 (CR-095 — 비전 파싱).
     *
     * PDF 페이지를 JPEG 이미지로 렌더한다 (텍스트 추출/OCR 아님). LLM 이 이미지를
     * 비전으로 직접 읽도록, BE 가 image 블록으로 변환해 컨텍스트에 주입한다.
     *
     * @param fileBase64 Base64 인코딩된 PDF
     * @param pages      페이지 범위(1-indexed, 예 "1-5", "3", "10-"). null/빈값=전체(maxPages 상한)
     * @param dpi        렌더 해상도 (기본 100)
     * @param maxPages   한 번에 변환할 최대 페이지 수
     * @return {success, images:[{page_number, media_type, data}], page_count, truncated, dpi}
     *         또는 {success:false, error}
     */
    public Map<String, Object> pdfToImages(String fileBase64, String pages, int dpi, int maxPages) {
        Map<String, Object> input = Map.of(
                "file_base64", fileBase64,
                "pages", pages != null ? pages : "",
                "dpi", dpi,
                "max_pages", maxPages
        );

        String result = mcpClient.callTool("pdf_to_images", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 self_rag_search 도구 호출.
     *
     * @return {query, results, iterations, relevance_scores, success}
     */
    public Map<String, Object> selfRagSearch(String query, String sourceId, int topK,
                                                double minScore, int maxIterations) {
        Map<String, Object> input = Map.of(
                "query", query,
                "source_id", sourceId,
                "top_k", topK,
                "min_score", minScore,
                "max_iterations", maxIterations
        );

        String result = mcpClient.callTool("self_rag_search", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 compress_context 도구 호출.
     *
     * @param documents JSON string of [{content, metadata}]
     * @return {compressed_documents, original_count, compressed_count, success}
     */
    public Map<String, Object> compressContext(String query, String documents,
                                                  double similarityThreshold) {
        Map<String, Object> input = Map.of(
                "query", query,
                "documents", documents,
                "similarity_threshold", similarityThreshold
        );

        String result = mcpClient.callTool("compress_context", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 embed_multimodal 도구 호출.
     *
     * @param items JSON string of [{type, content}]
     * @return {embeddings, model, dimensions, success}
     */
    public Map<String, Object> embedMultimodal(String items, String model) {
        Map<String, Object> input = Map.of(
                "items", items,
                "model", model != null ? model : ""
        );

        String result = mcpClient.callTool("embed_multimodal", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 scrape_url 도구 호출.
     *
     * @return {content, pages_scraped, url, success}
     */
    public Map<String, Object> scrapeUrl(String url, String mode, int maxPages,
                                            int timeoutMs) {
        Map<String, Object> input = Map.of(
                "url", url,
                "mode", mode != null ? mode : "single",
                "max_pages", maxPages,
                "timeout_ms", timeoutMs
        );

        String result = mcpClient.callTool("scrape_url", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 contextual_chunk 도구 호출 (PY-023).
     * Anthropic Contextual Retrieval: 청크별 LLM 문맥 접두사 생성.
     *
     * @return {chunks: [{content, context_prefix, metadata}], success}
     */
    public Map<String, Object> contextualChunk(String content, String documentContext,
                                                  Map<String, Object> chunkingConfig) {
        Map<String, Object> input = Map.of(
                "content", content,
                "document_context", documentContext != null ? documentContext : "",
                "chunking_config", toJsonString(chunkingConfig != null ? chunkingConfig : Map.of())
        );

        String result = mcpClient.callTool("contextual_chunk", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 parent_child_search 도구 호출 (PY-024).
     * Parent-Child 계층적 검색: child 매칭 → parent 반환.
     *
     * @return {results: [{content, parent_content, score, metadata}], success}
     */
    public Map<String, Object> parentChildSearch(String query, String sourceId, int topK) {
        Map<String, Object> input = Map.of(
                "query", query,
                "source_id", sourceId,
                "top_k", topK
        );

        String result = mcpClient.callTool("parent_child_search", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 evaluate_rag 도구 호출 (PY-026).
     * RAGAS 메트릭 평가.
     *
     * @return {metrics: {faithfulness, context_relevancy, ...}, sample_count, success}
     */
    public Map<String, Object> evaluateRag(String sourceId, List<Map<String, Object>> testSet,
                                              Map<String, Object> config) {
        return evaluateRag(sourceId, testSet, config, "fast");
    }

    public Map<String, Object> evaluateRag(String sourceId, List<Map<String, Object>> testSet,
                                              Map<String, Object> config, String mode) {
        Map<String, Object> input = new java.util.HashMap<>();
        input.put("source_id", sourceId);
        input.put("test_set", toJsonString(testSet != null ? testSet : List.of()));
        input.put("config", toJsonString(config != null ? config : Map.of()));
        input.put("mode", mode != null ? mode : "fast");

        String result = mcpClient.callTool("evaluate_rag", input);
        return parseJson(result);
    }

    /**
     * 범용 MCP 도구 호출. 새 도구 추가 시 개별 메서드 없이도 호출 가능.
     */
    public Map<String, Object> callToolRaw(String toolName, Map<String, Object> input) {
        String result = mcpClient.callTool(toolName, input);
        return parseJson(result);
    }

    private String toJsonString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }

    private Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse MCP response: " + json, e);
        }
    }
}
