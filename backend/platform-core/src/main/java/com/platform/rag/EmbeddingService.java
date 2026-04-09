package com.platform.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Spring AI EmbeddingModel을 래핑하는 서비스.
 * 기본 모델: OpenAI text-embedding-3-small (1536 차원)
 * OPENAI_API_KEY 미설정 시 EmbeddingModel 빈이 없으므로 이 서비스도 생성되지 않는다.
 */
@Component
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(@org.springframework.beans.factory.annotation.Autowired(required = false)
                             EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        if (embeddingModel == null) {
            log.warn("EmbeddingModel not available (OPENAI_API_KEY not set). Embedding features disabled.");
        }
    }

    public boolean isAvailable() {
        return embeddingModel != null;
    }

    /**
     * 단일 텍스트 임베딩 생성.
     *
     * @param text 임베딩할 텍스트
     * @return float[] 벡터 (1536 차원)
     */
    public float[] embed(String text) {
        if (embeddingModel == null) {
            throw new IllegalStateException("EmbeddingModel not available. Set OPENAI_API_KEY to enable embedding.");
        }
        EmbeddingResponse response = embeddingModel.embedForResponse(List.of(text));
        float[] vector = response.getResult().getOutput();
        log.debug("Embedded text ({}chars) → {}d vector", text.length(), vector.length);
        return vector;
    }

    /**
     * 배치 임베딩 생성.
     *
     * @param texts 임베딩할 텍스트 목록
     * @return List<float[]> 벡터 목록
     */
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        if (embeddingModel == null) {
            throw new IllegalStateException("EmbeddingModel not available. Set OPENAI_API_KEY to enable embedding.");
        }
        EmbeddingResponse response = embeddingModel.embedForResponse(texts);
        List<float[]> vectors = response.getResults().stream()
                .map(r -> r.getOutput())
                .collect(Collectors.toList());
        log.debug("Batch embedded {} texts", vectors.size());
        return vectors;
    }

    /**
     * pgvector 저장용 문자열 변환: float[] → "[0.1,0.2,...]"
     */
    public String vectorToString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
