"""PY-024: Parent-Child Hierarchical Search MCP Tool (CR-011).

Parent-Child 계층적 청킹 & 검색:
- 부모 청크: 큰 윈도우(예: 1024토큰) → 문맥 풍부
- 자식 청크: 작은 윈도우(예: 256토큰) → 정밀 매칭
- 검색: 자식으로 유사도 매칭 → 매칭된 부모 청크 반환
"""

import hashlib
import logging
import uuid
from typing import Any

from rag_pipeline.tools.chunker import chunk_document
from rag_pipeline.tools.embedder import embed_texts
from rag_pipeline.db import get_connection, vector_search

logger = logging.getLogger(__name__)

DEFAULT_PARENT_SIZE = 1024
DEFAULT_CHILD_SIZE = 256
DEFAULT_CHILD_OVERLAP = 50


def create_parent_child_chunks(
    content: str,
    config: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """문서를 부모-자식 계층 청크로 분할.

    Args:
        content: 원본 문서 텍스트
        config: {parent_chunk_size, child_chunk_size, child_overlap}

    Returns:
        {parents: [{id, content, content_hash, children: [{content, content_hash}]}], success}
    """
    cfg = config or {}
    parent_size = cfg.get("parent_chunk_size", DEFAULT_PARENT_SIZE)
    child_size = cfg.get("child_chunk_size", DEFAULT_CHILD_SIZE)
    child_overlap = cfg.get("child_overlap", DEFAULT_CHILD_OVERLAP)

    # 1단계: 부모 청크 생성 (큰 윈도우)
    parent_chunks = chunk_document(content, "fixed", {
        "max_chunk_size": parent_size,
        "overlap": 100,
    })

    parents = []
    for pi, parent in enumerate(parent_chunks):
        parent_text = parent["content"]
        parent_id = str(uuid.uuid4())
        parent_hash = hashlib.sha256(parent_text.encode()).hexdigest()

        # 2단계: 각 부모 내에서 자식 청크 생성 (작은 윈도우)
        child_chunks = _split_fixed(parent_text, child_size, child_overlap)
        children = []
        for ci, child_text in enumerate(child_chunks):
            child_hash = hashlib.sha256(child_text.encode()).hexdigest()
            children.append({
                "content": child_text,
                "content_hash": child_hash,
                "chunk_index": ci,
            })

        parents.append({
            "id": parent_id,
            "content": parent_text,
            "content_hash": parent_hash,
            "chunk_index": pi,
            "children": children,
        })

    total_children = sum(len(p["children"]) for p in parents)
    logger.info("Parent-child split: %d parents, %d children total", len(parents), total_children)

    return {"parents": parents, "total_parents": len(parents), "total_children": total_children, "success": True}


def parent_child_search(
    query: str,
    source_id: str,
    top_k: int = 5,
) -> dict[str, Any]:
    """Parent-child 계층적 검색: child로 매칭 → parent 반환.

    DB에서 parent_id가 설정된 child 청크로 벡터 검색 후,
    매칭된 parent의 풍부한 컨텍스트를 반환.

    Args:
        query: 검색 쿼리
        source_id: 지식 소스 ID
        top_k: 반환할 부모 청크 수

    Returns:
        {results: [{content, parent_id, score, metadata}], success}
    """
    conn = get_connection()
    try:
        # 쿼리 임베딩
        embed_result = embed_texts([query])
        query_vector = embed_result["embeddings"][0]

        with conn.cursor() as cur:
            # child에서 유사도 검색 → parent JOIN
            cur.execute(
                """
                WITH matched_children AS (
                    SELECT c.parent_id, 1 - (c.embedding <=> %s::vector) AS score
                    FROM embeddings c
                    WHERE c.source_id = %s AND c.parent_id IS NOT NULL
                    ORDER BY c.embedding <=> %s::vector
                    LIMIT %s
                ),
                distinct_parents AS (
                    SELECT parent_id, MAX(score) AS score
                    FROM matched_children
                    GROUP BY parent_id
                )
                SELECT p.content, p.context_prefix, p.metadata, dp.parent_id, dp.score
                FROM distinct_parents dp
                JOIN embeddings p ON p.id::text = dp.parent_id
                ORDER BY dp.score DESC
                LIMIT %s
                """,
                (query_vector, source_id, query_vector, top_k * 3, top_k),
            )

            results = []
            for row in cur.fetchall():
                content = row[0]
                context_prefix = row[1]
                metadata = row[2] or {}
                parent_id = row[3]
                score = float(row[4])

                # context_prefix가 있으면 결합
                full_content = f"{context_prefix}\n{content}" if context_prefix else content

                results.append({
                    "content": full_content,
                    "parent_id": parent_id,
                    "score": score,
                    "metadata": metadata,
                })

        logger.info("Parent-child search: %d parents found for query in source '%s'", len(results), source_id)
        return {"results": results, "result_count": len(results), "success": True}

    finally:
        conn.close()


def _split_fixed(text: str, chunk_size: int, overlap: int) -> list[str]:
    """고정 크기로 텍스트 분할."""
    chunks = []
    start = 0
    while start < len(text):
        end = start + chunk_size
        chunks.append(text[start:end])
        start = end - overlap
        if start >= len(text):
            break
    return chunks
