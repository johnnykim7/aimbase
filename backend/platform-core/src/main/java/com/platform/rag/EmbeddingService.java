package com.platform.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Spring AI EmbeddingModel을 래핑하는 서비스.
 * 기본 모델: OpenAI text-embedding-3-small (1536 차원)
 *
 * spring.ai.openai.api-key가 설정되지 않으면 빈이 생성되지 않음.
 */
@Component
@ConditionalOnProperty(name = "spring.ai.openai.api-key", matchIfMissing = false)
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 단일 텍스트 임베딩 생성.
     *
     * @param text 임베딩할 텍스트
     * @return float[] 벡터 (차원은 모델에 따라 다름: OpenAI 1536d, BGE-M3 1024d 등)
     */
    public float[] embed(String text) {
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
