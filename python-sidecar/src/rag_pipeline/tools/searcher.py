"""PY-004: Hybrid Search MCP Tool.

Combines BM25 keyword search and pgvector semantic search using RRF.
"""

import re

from rank_bm25 import BM25Okapi

from rag_pipeline.db import keyword_search_contents, vector_search
from rag_pipeline.tools.embedder import embed_single


def _tokenize(text: str) -> list[str]:
    """Simple tokenizer that handles Korean and English."""
    # Split on whitespace and punctuation, keep meaningful tokens
    tokens = re.findall(r'[가-힣]+|[a-zA-Z]+|[0-9]+', text.lower())
    # Filter very short tokens
    return [t for t in tokens if len(t) > 1]


def _rrf_score(rank: int, k: int = 60) -> float:
    """Reciprocal Rank Fusion score."""
    return 1.0 / (k + rank + 1)


def search_hybrid(
    query: str,
    source_id: str,
    top_k: int = 5,
    vector_weight: float = 0.7,
    keyword_weight: float = 0.3,
    embedding_model: str = "",
) -> list[dict]:
    """Hybrid search combining BM25 and vector similarity.

    Args:
        query: Search query text
        source_id: Knowledge source ID
        top_k: Number of results to return
        vector_weight: Weight for vector search scores
        keyword_weight: Weight for BM25 keyword scores
        embedding_model: 쿼리 임베딩에 사용할 모델 (빈 문자열이면 기본 모델)

    Returns:
        List of {content, metadata, vector_score, keyword_score, combined_score}
    """
    # 1) Vector search via pgvector
    query_vector = embed_single(query, model=embedding_model)
    vector_results = vector_search(source_id, query_vector, top_k=top_k * 2)

    # 2) BM25 keyword search
    all_chunks = keyword_search_contents(source_id)
    if not all_chunks:
        return vector_results[:top_k]

    tokenized_corpus = [_tokenize(doc["content"]) for doc in all_chunks]
    bm25 = BM25Okapi(tokenized_corpus)
    bm25_scores = bm25.get_scores(_tokenize(query))

    # Build BM25 ranked results
    bm25_ranked = sorted(
        enumerate(bm25_scores), key=lambda x: x[1], reverse=True
    )

    # 3) RRF fusion
    # Index vector results by content for matching
    content_scores: dict[str, dict] = {}

    for rank, vr in enumerate(vector_results):
        key = vr["content"]
        content_scores[key] = {
            "content": vr["content"],
            "metadata": vr["metadata"],
            "vector_score": vr["vector_score"],
            "keyword_score": 0.0,
            "rrf_vector": _rrf_score(rank),
            "rrf_keyword": 0.0,
        }

    for rank, (idx, score) in enumerate(bm25_ranked[:top_k * 2]):
        key = all_chunks[idx]["content"]
        if key in content_scores:
            content_scores[key]["keyword_score"] = float(score)
            content_scores[key]["rrf_keyword"] = _rrf_score(rank)
        else:
            content_scores[key] = {
                "content": key,
                "metadata": all_chunks[idx].get("metadata", {}),
                "vector_score": 0.0,
                "keyword_score": float(score),
                "rrf_vector": 0.0,
                "rrf_keyword": _rrf_score(rank),
            }

    # Calculate combined score using weighted RRF
    for entry in content_scores.values():
        entry["combined_score"] = (
            vector_weight * entry["rrf_vector"]
            + keyword_weight * entry["rrf_keyword"]
        )

    # Sort by combined score and return top_k
    sorted_results = sorted(
        content_scores.values(),
        key=lambda x: x["combined_score"],
        reverse=True,
    )[:top_k]

    return [
        {
            "content": r["content"],
            "metadata": r["metadata"],
            "vector_score": r["vector_score"],
            "keyword_score": r["keyword_score"],
            "combined_score": r["combined_score"],
        }
        for r in sorted_results
    ]
