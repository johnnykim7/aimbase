"""PY-015: Context Compression.

쿼리와의 유사도가 낮은 문장을 제거하여 컨텍스트를 압축한다.
sentence-transformers 코사인 유사도 기반.
"""

import logging
import re
from typing import Any

logger = logging.getLogger(__name__)

# Korean sentence boundary pattern: 다/요/까/죠 + period, !, ?
_SENTENCE_SPLIT_RE = re.compile(
    r'(?<=[.!?])\s+'           # standard sentence boundary
    r'|(?<=[다요까죠][.!?])\s*'  # Korean endings + punctuation
    r'|(?<=[다요까죠])\s+(?=[A-Z가-힣])'  # Korean endings followed by new sentence
)


def _split_sentences(text: str) -> list[str]:
    """Split text into sentences handling Korean boundaries."""
    if not text or not text.strip():
        return []
    # Split by the pattern, then filter empty
    parts = _SENTENCE_SPLIT_RE.split(text.strip())
    return [s.strip() for s in parts if s and s.strip()]


def _estimate_tokens(text: str) -> int:
    """Rough token count approximation: len(text) // 3."""
    return len(text) // 3


def compress_context(
    query: str,
    documents: list[dict[str, Any]],
    similarity_threshold: float = 0.5,
) -> dict[str, Any]:
    """컨텍스트 압축: 쿼리와 유사도 낮은 문장 제거.

    Args:
        query: 검색 쿼리
        documents: [{content: str, metadata: dict, ...}] 문서 목록
        similarity_threshold: 유사도 임계값 (0.0~1.0)

    Returns:
        {compressed_documents, original_token_count, compressed_token_count, compression_ratio}
    """
    if not documents:
        return {
            "compressed_documents": [],
            "original_token_count": 0,
            "compressed_token_count": 0,
            "compression_ratio": 1.0,
        }

    try:
        from sentence_transformers import SentenceTransformer, util as st_util

        model = SentenceTransformer("all-MiniLM-L6-v2")
        query_embedding = model.encode(query, convert_to_tensor=True)
    except Exception as e:
        logger.warning("sentence-transformers unavailable, returning docs as-is: %s", e)
        return _fallback_passthrough(documents)

    original_token_count = 0
    compressed_token_count = 0
    compressed_documents: list[dict[str, Any]] = []

    for doc in documents:
        content = doc.get("content", "")
        metadata = doc.get("metadata", {})
        original_token_count += _estimate_tokens(content)

        sentences = _split_sentences(content)
        if not sentences:
            compressed_documents.append({
                "content": content,
                "metadata": metadata,
                "sentences_removed": 0,
            })
            compressed_token_count += _estimate_tokens(content)
            continue

        # Compute similarities
        sentence_embeddings = model.encode(sentences, convert_to_tensor=True)
        similarities = st_util.cos_sim(query_embedding, sentence_embeddings)[0]

        # Filter sentences above threshold
        kept: list[str] = []
        removed_count = 0
        best_idx = int(similarities.argmax())

        for i, (sent, sim) in enumerate(zip(sentences, similarities)):
            if float(sim) >= similarity_threshold:
                kept.append(sent)
            else:
                removed_count += 1

        # Preserve at least 1 sentence per document
        if not kept:
            kept.append(sentences[best_idx])
            removed_count -= 1

        compressed_content = " ".join(kept)
        compressed_token_count += _estimate_tokens(compressed_content)

        compressed_documents.append({
            "content": compressed_content,
            "metadata": metadata,
            "sentences_removed": removed_count,
        })

    ratio = compressed_token_count / max(original_token_count, 1)

    return {
        "compressed_documents": compressed_documents,
        "original_token_count": original_token_count,
        "compressed_token_count": compressed_token_count,
        "compression_ratio": round(ratio, 4),
    }


def _fallback_passthrough(documents: list[dict[str, Any]]) -> dict[str, Any]:
    """sentence-transformers 사용 불가 시 문서를 그대로 반환."""
    original_tokens = 0
    result_docs = []
    for doc in documents:
        content = doc.get("content", "")
        original_tokens += _estimate_tokens(content)
        result_docs.append({
            "content": content,
            "metadata": doc.get("metadata", {}),
            "sentences_removed": 0,
        })
    return {
        "compressed_documents": result_docs,
        "original_token_count": original_tokens,
        "compressed_token_count": original_tokens,
        "compression_ratio": 1.0,
    }
