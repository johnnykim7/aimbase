package com.platform.rag;

import com.platform.domain.RetrievalConfigEntity;
import com.platform.rag.model.RetrievedChunk;
import com.platform.repository.RetrievalConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

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

    public RAGService(VectorSearcher vectorSearcher,
                      RetrievalConfigRepository retrievalConfigRepository) {
        this.vectorSearcher = vectorSearcher;
        this.retrievalConfigRepository = retrievalConfigRepository;
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

        List<RetrievedChunk> chunks = vectorSearcher.search(query, sourceId, k);

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

        // 컨텍스트 템플릿이 설정되어 있으면 사용, 아니면 기본 포맷
        String header = (contextTemplate != null && !contextTemplate.isBlank())
                ? contextTemplate
                : "다음 참고 자료를 바탕으로 답변하세요:";

        StringBuilder sb = new StringBuilder();
        sb.append(header).append("\n\n");

        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            sb.append("[").append(i + 1).append("] ")
              .append(chunk.content())
              .append("\n출처: ").append(chunk.sourceId())
              .append(String.format(", 유사도: %.3f", chunk.score()))
              .append("\n\n");
        }

        return sb.toString().strip();
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
