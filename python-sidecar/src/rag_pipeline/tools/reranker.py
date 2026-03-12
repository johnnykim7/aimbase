"""PY-003: Reranking MCP Tool.

Re-sorts search results using a cross-encoder model for improved accuracy.
"""

from sentence_transformers import CrossEncoder

from rag_pipeline.config import settings

_reranker: CrossEncoder | None = None


def _get_reranker(model_name: str = "") -> CrossEncoder:
    global _reranker
    name = model_name or settings.DEFAULT_RERANK_MODEL
    if _reranker is None:
        _reranker = CrossEncoder(name)
    return _reranker


def rerank_results(
    query: str,
    documents: list[dict],
    top_k: int = 5,
    model: str = "",
) -> list[dict]:
    """Re-rank documents using cross-encoder.

    Args:
        query: Original query text
        documents: List of {content, metadata, ...}
        top_k: Number of top results to return
        model: Cross-encoder model name

    Returns:
        List of {content, metadata, rerank_score} sorted by rerank_score desc
    """
    if not documents:
        return []

    reranker = _get_reranker(model)

    # Prepare query-document pairs
    pairs = [(query, doc["content"]) for doc in documents]
    scores = reranker.predict(pairs)

    # Attach scores and sort
    scored_docs = []
    for doc, score in zip(documents, scores):
        scored_docs.append({
            "content": doc["content"],
            "metadata": doc.get("metadata", {}),
            "rerank_score": float(score),
        })

    scored_docs.sort(key=lambda x: x["rerank_score"], reverse=True)
    return scored_docs[:top_k]
