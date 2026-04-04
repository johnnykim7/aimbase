package com.platform.rag;

import com.platform.domain.RetrievalConfigEntity;
import com.platform.rag.model.RetrievedChunk;
import com.platform.repository.RetrievalConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * RAG 컨텍스트를 LLM에 주입할 문자열로 빌드하는 서비스.
 *
 * B9: RetrievalConfig가 존재하면 해당 설정(topK, similarityThreshold, contextTemplate 등)을 적용.
 */
@Component
public class RAGService {

    private static final Logger log = LoggerFactory.getLogger(RAGService.class);
    private static final int DEFAULT_TOP_K = 5;
    private static final BigDecimal DEFAULT_SIMILARITY_THRESHOLD = new BigDecimal("0.0");

    private final VectorSearcher vectorSearcher;
    private final RetrievalConfigRepository retrievalConfigRepository;
    private final MCPRagClient mcpRagClient;

    public RAGService(VectorSearcher vectorSearcher,
                      RetrievalConfigRepository retrievalConfigRepository,
                      MCPRagClient mcpRagClient) {
        this.vectorSearcher = vectorSearcher;
        this.retrievalConfigRepository = retrievalConfigRepository;
        this.mcpRagClient = mcpRagClient;
    }

    /**
     * 쿼리와 유사한 청크를 검색하여 LLM 시스템 프롬프트용 컨텍스트 문자열 생성.
     * 활성화된 RetrievalConfig가 존재하면 해당 설정(topK, similarityThreshold, contextTemplate)을 적용.
     *
     * @param query    사용자 쿼리
     * @param sourceId 검색 대상 지식 소스 ID
     * @param topK     최대 청크 수 (0 이하면 기본값 5 사용, RetrievalConfig가 있으면 무시)
     * @return 시스템 프롬프트로 주입할 컨텍스트 문자열
     */
    public String buildContext(String query, String sourceId, int topK) {
        // B9: 활성 RetrievalConfig 로드 (sourceId를 config ID로 조회, 없으면 첫 번째 활성 설정 사용)
        RetrievalConfigEntity config = resolveConfig(sourceId);

        int k = DEFAULT_TOP_K;
        BigDecimal similarityThreshold = DEFAULT_SIMILARITY_THRESHOLD;
        String contextTemplate = null;

        if (config != null) {
            k = config.getTopK();
            similarityThreshold = config.getSimilarityThreshold() != null
                    ? config.getSimilarityThreshold() : DEFAULT_SIMILARITY_THRESHOLD;
            contextTemplate = config.getContextTemplate();
            log.debug("Using RetrievalConfig '{}': topK={}, threshold={}, searchType={}",
                    config.getId(), k, similarityThreshold, config.getSearchType());
        } else {
            k = topK > 0 ? topK : DEFAULT_TOP_K;
        }

        // PRD-119: Query Transform 오케스트레이션
        String searchType = config != null ? config.getSearchType() : null;
        String effectiveQuery = query;
        List<String> additionalQueries = List.of();

        String transformStrategy = config != null && config.getQueryProcessing() != null
                ? (String) config.getQueryProcessing().get("transform_strategy") : null;

        if (transformStrategy != null && mcpRagClient.isAvailable()) {
            String strategy = transformStrategy;
            try {
                Map<String, Object> transformResult = mcpRagClient.transformQuery(query, strategy);
                Object transformed = transformResult.get("transformed_queries");
                if (transformed instanceof List<?> tList && !tList.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    List<String> tQueries = (List<String>) tList;
                    effectiveQuery = tQueries.get(0);
                    additionalQueries = tQueries.size() > 1 ? tQueries.subList(1, tQueries.size()) : List.of();
                    log.debug("Query transform '{}': {} → {} queries", strategy, query, tQueries.size());
                }
            } catch (Exception e) {
                log.warn("Query transform failed, using original query: {}", e.getMessage());
            }
        }

        // PRD-117: parent_child 검색 타입이면 계층적 검색
        List<RetrievedChunk> chunks;
        if ("parent_child".equals(searchType)) {
            chunks = vectorSearcher.searchParentChild(effectiveQuery, sourceId, k);
        } else {
            chunks = vectorSearcher.search(effectiveQuery, sourceId, k);
        }

        // Multi-Query: 추가 쿼리로 검색 후 결과 병합 (중복 제거)
        if (!additionalQueries.isEmpty()) {
            Set<String> seenContents = new LinkedHashSet<>();
            chunks.forEach(c -> seenContents.add(c.content()));

            for (String addQuery : additionalQueries) {
                List<RetrievedChunk> addResults = "parent_child".equals(searchType)
                        ? vectorSearcher.searchParentChild(addQuery, sourceId, k)
                        : vectorSearcher.search(addQuery, sourceId, k);
                for (RetrievedChunk r : addResults) {
                    if (seenContents.add(r.content())) {
                        chunks = new ArrayList<>(chunks);
                        chunks.add(r);
                    }
                }
            }

            // 점수 내림차순 재정렬 후 topK 제한
            chunks = chunks.stream()
                    .sorted((a, b) -> Double.compare(b.score(), a.score()))
                    .limit(k)
                    .toList();
        }

        // 유사도 임계값 필터링
        BigDecimal threshold = similarityThreshold;
        if (threshold.compareTo(BigDecimal.ZERO) > 0) {
            chunks = chunks.stream()
                    .filter(c -> BigDecimal.valueOf(c.score()).compareTo(threshold) >= 0)
                    .toList();
        }

        if (chunks.isEmpty()) {
            log.debug("No relevant chunks found for query='{}' in sourceId='{}'", query, sourceId);
            return "";
        }

        log.debug("Building RAG context from {} chunks for query='{}'", chunks.size(), query);

        // 컨텍스트 템플릿이 설정되어 있으면 사용, 아니면 기본 RAG 시스템 프롬프트
        StringBuilder sb = new StringBuilder();

        if (contextTemplate != null && !contextTemplate.isBlank()) {
            sb.append(contextTemplate).append("\n\n");
        } else {
            sb.append("당신은 제공된 문서를 기반으로 정확하게 답변하는 AI 어시스턴트입니다.\n\n");
            sb.append("## 규칙\n");
            sb.append("1. 반드시 아래 [참고 문서]의 내용만을 근거로 답변하세요.\n");
            sb.append("2. 참고 문서에 없는 내용은 \"제공된 문서에서 해당 정보를 찾을 수 없습니다\"라고 답하세요.\n");
            sb.append("3. 답변 시 출처를 [1], [2] 형식으로 인용 표시하세요.\n");
            sb.append("4. 여러 문서의 내용을 종합하여 답변할 수 있지만, 반드시 각 근거의 출처를 명시하세요.\n");
            sb.append("5. 추측이나 외부 지식을 사용하지 마세요.\n\n");
        }

        sb.append("## 참고 문서\n\n");

        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            sb.append("[").append(i + 1).append("] ")
              .append(chunk.content())
              .append("\n(출처: ").append(chunk.sourceId())
              .append(String.format(", 유사도: %.3f)", chunk.score()))
              .append("\n\n");
        }

        return sb.toString().strip();
    }

    /**
     * PRD-124: 시맨틱 캐시를 포함한 컨텍스트 빌드.
     * 캐시 히트 시 DB 검색 없이 즉시 반환, 미스 시 검색 후 캐시 저장.
     */
    @SuppressWarnings("unchecked")
    public String buildContextWithCache(String query, String sourceId, int topK) {
        if (mcpRagClient.isAvailable()) {
            try {
                // 캐시 조회
                Map<String, Object> cacheResult = mcpRagClient.callToolRaw("cache_lookup", Map.of(
                        "query", query, "source_id", sourceId, "threshold", 0.95));
                if (Boolean.TRUE.equals(cacheResult.get("hit"))) {
                    log.info("Semantic cache HIT for query='{}', similarity={}",
                            query, cacheResult.get("similarity"));
                    return (String) cacheResult.get("response_text");
                }
            } catch (Exception e) {
                log.debug("Cache lookup failed: {}", e.getMessage());
            }
        }

        // 캐시 미스: 일반 검색
        String context = buildContext(query, sourceId, topK);

        // 검색 결과를 캐시에 저장
        if (!context.isEmpty() && mcpRagClient.isAvailable()) {
            try {
                mcpRagClient.callToolRaw("cache_store", Map.of(
                        "query", query, "source_id", sourceId,
                        "response_text", context, "metadata", "{}", "ttl_hours", 24));
            } catch (Exception e) {
                log.debug("Cache store failed: {}", e.getMessage());
            }
        }

        return context;
    }

    /**
     * PRD-125: 인용 번호 포함 컨텍스트 빌드.
     * [1][2] 형태의 인라인 인용과 citations 메타데이터를 함께 반환.
     *
     * @return {context: String, citations: List<Map>}
     */
    public Map<String, Object> buildContextWithCitations(String query, String sourceId, int topK) {
        String context = buildContext(query, sourceId, topK);

        // citations 메타데이터 구성 (BIZ-028)
        List<Map<String, Object>> citations = new ArrayList<>();
        // 기존 buildContext가 [1] [2] 형식으로 포함하므로 파싱
        String[] lines = context.split("\n");
        int citationIndex = 0;
        for (String line : lines) {
            if (line.startsWith("[") && line.contains("]")) {
                citationIndex++;
                citations.add(Map.of(
                        "index", citationIndex,
                        "source_id", sourceId,
                        "excerpt", line.length() > 100 ? line.substring(0, 100) + "..." : line
                ));
            }
        }

        return Map.of("context", context, "citations", citations);
    }

    /**
     * sourceId에 매칭되는 RetrievalConfig를 찾거나, 첫 번째 활성 설정을 반환.
     */
    private RetrievalConfigEntity resolveConfig(String sourceId) {
        // 1. sourceId와 동일한 ID의 config가 있으면 사용
        var byId = retrievalConfigRepository.findById(sourceId);
        if (byId.isPresent() && byId.get().isActive()) {
            return byId.get();
        }
        // 2. 활성 config 중 첫 번째 사용 (전역 기본 설정)
        List<RetrievalConfigEntity> activeConfigs = retrievalConfigRepository.findByIsActiveTrue();
        return activeConfigs.isEmpty() ? null : activeConfigs.get(0);
    }
}
