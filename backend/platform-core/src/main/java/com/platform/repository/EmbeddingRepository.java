package com.platform.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.EmbeddingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * pgvector 연산자를 사용하는 EmbeddingRepository.
 * JPA가 <=> 연산자를 지원하지 않으므로 JdbcTemplate 직접 사용.
 */
@Repository
public class EmbeddingRepository {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingRepository.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EmbeddingRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 임베딩 저장. embedding 필드는 pgvector 리터럴("[0.1,0.2,...]"::vector)로 INSERT.
     */
    public void save(EmbeddingEntity entity) {
        String metadataJson = toJson(entity.getMetadata());
        jdbc.update(
                """
                INSERT INTO embeddings (id, source_id, document_id, chunk_index, content, embedding, metadata, created_at)
                VALUES (?::uuid, ?, ?, ?, ?, ?::vector, ?::jsonb, now())
                ON CONFLICT (id) DO UPDATE SET
                    content = EXCLUDED.content,
                    embedding = EXCLUDED.embedding,
                    metadata = EXCLUDED.metadata
                """,
                entity.getId() != null ? entity.getId().toString() : UUID.randomUUID().toString(),
                entity.getSourceId(),
                entity.getDocumentId(),
                entity.getChunkIndex(),
                entity.getContent(),
                entity.getEmbedding(),
                metadataJson
        );
    }

    /**
     * 배치 저장.
     */
    public void saveAll(List<EmbeddingEntity> entities) {
        for (EmbeddingEntity entity : entities) {
            save(entity);
        }
    }

    /**
     * pgvector 코사인 유사도 검색.
     * 1 - (embedding <=> queryVector::vector) 를 score로 반환.
     *
     * @param sourceId    지식 소스 ID
     * @param queryVector 쿼리 임베딩 벡터
     * @param topK        반환할 최대 청크 수
     * @return 유사도 점수 순으로 정렬된 EmbeddingEntity 목록 (score는 metadata에 담겨 반환)
     */
    public List<EmbeddingEntity> findSimilar(String sourceId, float[] queryVector, int topK) {
        String vectorLiteral = toVectorLiteral(queryVector);
        return jdbc.query(
                """
                SELECT id, source_id, document_id, chunk_index, content, metadata,
                       1 - (embedding <=> ?::vector) AS score
                FROM embeddings
                WHERE source_id = ?
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """,
                new EmbeddingRowMapperWithScore(),
                vectorLiteral, sourceId, vectorLiteral, topK
        );
    }

    /**
     * 특정 소스의 임베딩 전체 삭제.
     */
    public int deleteBySourceId(String sourceId) {
        return jdbc.update("DELETE FROM embeddings WHERE source_id = ?", sourceId);
    }

    /**
     * 특정 소스의 임베딩 수 조회.
     */
    public int countBySourceId(String sourceId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM embeddings WHERE source_id = ?",
                Integer.class, sourceId);
        return count != null ? count : 0;
    }

    // ─── 내부 유틸 ──────────────────────────────────────────────────────────

    /**
     * float[] → "[0.1,0.2,...]" pgvector 리터럴 변환.
     */
    public static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private String toJson(Object obj) {
        if (obj == null) return "{}";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metadata to JSON: {}", e.getMessage());
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse metadata JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    private class EmbeddingRowMapperWithScore implements RowMapper<EmbeddingEntity> {
        @Override
        public EmbeddingEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            EmbeddingEntity entity = new EmbeddingEntity();
            entity.setSourceId(rs.getString("source_id"));
            entity.setDocumentId(rs.getString("document_id"));
            entity.setChunkIndex(rs.getInt("chunk_index"));
            entity.setContent(rs.getString("content"));

            // metadata + score를 함께 metadata Map에 병합
            String metadataStr = rs.getString("metadata");
            Map<String, Object> meta = fromJson(metadataStr);
            java.util.Map<String, Object> mutableMeta = new java.util.LinkedHashMap<>(meta);
            mutableMeta.put("_score", rs.getDouble("score"));
            entity.setMetadata(mutableMeta);

            return entity;
        }
    }
}
