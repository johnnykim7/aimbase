"""PY-028: Semantic Cache MCP Tool (CR-011).

Redis + pgvector 기반 시맨틱 캐시.
쿼리 임베딩 간 코사인 유사도 > 0.95 이면 캐시 히트 (BIZ-027).
"""

import logging
from typing import Any

from rag_pipeline.db import get_connection
from rag_pipeline.tools.embedder import embed_texts

logger = logging.getLogger(__name__)

SIMILARITY_THRESHOLD = 0.95


def cache_lookup(
    query: str,
    source_id: str,
    threshold: float = SIMILARITY_THRESHOLD,
) -> dict[str, Any]:
    """시맨틱 캐시에서 유사 쿼리 검색.

    Args:
        query: 검색 쿼리
        source_id: 지식 소스 ID
        threshold: 유사도 임계값 (기본 0.95)

    Returns:
        {hit: bool, response_text?, similarity?, cache_id?}
    """
    embed_result = embed_texts([query])
    query_vector = embed_result["embeddings"][0]

    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, response_text, 1 - (query_embedding <=> %s::vector) AS similarity
                FROM semantic_cache
                WHERE source_id = %s
                  AND (expires_at IS NULL OR expires_at > now())
                ORDER BY query_embedding <=> %s::vector
                LIMIT 1
                """,
                (query_vector, source_id, query_vector),
            )
            row = cur.fetchone()

            if row and float(row[2]) >= threshold:
                cache_id = str(row[0])
                # 히트 카운트 업데이트
                cur.execute(
                    "UPDATE semantic_cache SET hit_count = hit_count + 1, last_hit_at = now() WHERE id = %s",
                    (cache_id,),
                )
                logger.info("Cache HIT: similarity=%.4f for source '%s'", float(row[2]), source_id)
                return {
                    "hit": True,
                    "response_text": row[1],
                    "similarity": round(float(row[2]), 4),
                    "cache_id": cache_id,
                }

        logger.debug("Cache MISS for source '%s'", source_id)
        return {"hit": False}

    finally:
        conn.close()


def cache_store(
    query: str,
    source_id: str,
    response_text: str,
    metadata: dict[str, Any] | None = None,
    ttl_hours: int = 24,
) -> dict[str, Any]:
    """시맨틱 캐시에 쿼리-응답 쌍 저장.

    Args:
        query: 원본 쿼리
        source_id: 지식 소스 ID
        response_text: 캐시할 응답 텍스트
        metadata: 추가 메타데이터
        ttl_hours: 캐시 TTL (시간)

    Returns:
        {cache_id, success}
    """
    import uuid

    embed_result = embed_texts([query])
    query_vector = embed_result["embeddings"][0]

    conn = get_connection()
    try:
        cache_id = str(uuid.uuid4())
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO semantic_cache (id, source_id, query_text, query_embedding,
                                            response_text, metadata, expires_at)
                VALUES (%s, %s, %s, %s::vector, %s, %s::jsonb,
                        CASE WHEN %s > 0 THEN now() + interval '1 hour' * %s ELSE NULL END)
                """,
                (cache_id, source_id, query, query_vector, response_text,
                 _to_json(metadata or {}), ttl_hours, ttl_hours),
            )

        logger.info("Cache STORE: id=%s for source '%s'", cache_id, source_id)
        return {"cache_id": cache_id, "success": True}

    finally:
        conn.close()


def cache_invalidate(source_id: str) -> dict[str, Any]:
    """특정 소스의 캐시 전체 무효화."""
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM semantic_cache WHERE source_id = %s", (source_id,))
            deleted = cur.rowcount
        logger.info("Cache INVALIDATE: %d entries for source '%s'", deleted, source_id)
        return {"deleted": deleted, "success": True}
    finally:
        conn.close()


def _to_json(obj: Any) -> str:
    import json
    return json.dumps(obj, ensure_ascii=False)
