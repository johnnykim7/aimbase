package com.platform.rag;

import com.platform.rag.model.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 컨텍스트를 LLM에 주입할 문자열로 빌드하는 서비스.
 */
@Component
public class RAGService {

    private static final Logger log = LoggerFactory.getLogger(RAGService.class);
    private static final int DEFAULT_TOP_K = 5;

    private final VectorSearcher vectorSearcher;

    public RAGService(VectorSearcher vectorSearcher) {
        this.vectorSearcher = vectorSearcher;
    }

    /**
     * 쿼리와 유사한 청크를 검색하여 LLM 시스템 프롬프트용 컨텍스트 문자열 생성.
     *
     * @param query    사용자 쿼리
     * @param sourceId 검색 대상 지식 소스 ID
     * @param topK     최대 청크 수 (0 이하면 기본값 5 사용)
     * @return 시스템 프롬프트로 주입할 컨텍스트 문자열
     */
    public String buildContext(String query, String sourceId, int topK) {
        int k = topK > 0 ? topK : DEFAULT_TOP_K;
        List<RetrievedChunk> chunks = vectorSearcher.search(query, sourceId, k);

        if (chunks.isEmpty()) {
            log.debug("No relevant chunks found for query='{}' in sourceId='{}'", query, sourceId);
            return "";
        }

        log.debug("Building RAG context from {} chunks for query='{}'", chunks.size(), query);

        StringBuilder sb = new StringBuilder();
        sb.append("다음 참고 자료를 바탕으로 답변하세요:\n\n");

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
}
