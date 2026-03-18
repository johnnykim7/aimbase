"""PY-021: Embedding Drift Detection.

pgvector에 저장된 임베딩의 분포 변화를 감지하여
재인덱싱 필요 여부를 판단한다.
"""

import logging
from typing import Any

logger = logging.getLogger(__name__)

# 드리프트 임계값
DRIFT_THRESHOLD = 0.3


def detect_embedding_drift(
    source_id: str,
    sample_size: int = 100,
    baseline_stats: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """임베딩 분포 드리프트를 감지.

    Args:
        source_id: Knowledge source ID
        sample_size: 랜덤 샘플링할 임베딩 수
        baseline_stats: 이전 측정 기준 {mean: float, std: float}

    Returns:
        {drift_score, mean_similarity, std_similarity, sample_size, recommendation}
    """
    try:
        embeddings = _fetch_embeddings(source_id, sample_size)
    except Exception as e:
        logger.error("Failed to fetch embeddings for source %s: %s", source_id, e)
        return {
            "error": f"DB connection failed: {str(e)}",
            "drift_score": 0.0,
            "mean_similarity": 0.0,
            "std_similarity": 0.0,
            "sample_size": 0,
            "recommendation": "error",
        }

    if len(embeddings) < 2:
        return {
            "error": "Insufficient embeddings for drift analysis",
            "drift_score": 0.0,
            "mean_similarity": 0.0,
            "std_similarity": 0.0,
            "sample_size": len(embeddings),
            "recommendation": "insufficient_data",
        }

    # Compute pairwise cosine similarities
    try:
        import numpy as np

        emb_array = np.array(embeddings)
        # Normalize
        norms = np.linalg.norm(emb_array, axis=1, keepdims=True)
        norms = np.where(norms == 0, 1, norms)
        normalized = emb_array / norms

        # Pairwise cosine similarity (upper triangle only)
        sim_matrix = normalized @ normalized.T
        n = sim_matrix.shape[0]
        upper_indices = np.triu_indices(n, k=1)
        pairwise_sims = sim_matrix[upper_indices]

        current_mean = float(np.mean(pairwise_sims))
        current_std = float(np.std(pairwise_sims))
    except ImportError:
        logger.warning("numpy not available — using pure Python fallback")
        current_mean, current_std = _compute_stats_pure(embeddings)

    # Compute drift score
    drift_score = 0.0
    if baseline_stats and "mean" in baseline_stats and "std" in baseline_stats:
        baseline_mean = float(baseline_stats["mean"])
        baseline_std = float(baseline_stats["std"])
        if baseline_std > 0:
            drift_score = abs(current_mean - baseline_mean) / baseline_std
        else:
            drift_score = abs(current_mean - baseline_mean)

    recommendation = "reindex_recommended" if drift_score > DRIFT_THRESHOLD else "stable"

    return {
        "drift_score": round(drift_score, 4),
        "mean_similarity": round(current_mean, 4),
        "std_similarity": round(current_std, 4),
        "sample_size": len(embeddings),
        "recommendation": recommendation,
    }


def _fetch_embeddings(source_id: str, sample_size: int) -> list[list[float]]:
    """pgvector DB에서 랜덤 임베딩 샘플을 가져온다."""
    import psycopg2

    from evaluation.config import settings

    conn = psycopg2.connect(settings.db_url)
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT embedding::text
                FROM knowledge_chunks
                WHERE source_id = %s
                  AND embedding IS NOT NULL
                ORDER BY RANDOM()
                LIMIT %s
                """,
                (source_id, sample_size),
            )
            rows = cur.fetchall()

        embeddings = []
        for (emb_text,) in rows:
            # pgvector text format: [0.1,0.2,...]
            cleaned = emb_text.strip("[]")
            if cleaned:
                vec = [float(x) for x in cleaned.split(",")]
                embeddings.append(vec)

        return embeddings
    finally:
        conn.close()


def _compute_stats_pure(embeddings: list[list[float]]) -> tuple[float, float]:
    """numpy 없이 순수 Python으로 쌍별 코사인 유사도 통계를 계산."""
    import math

    def cosine_sim(a: list[float], b: list[float]) -> float:
        dot = sum(x * y for x, y in zip(a, b))
        norm_a = math.sqrt(sum(x * x for x in a))
        norm_b = math.sqrt(sum(x * x for x in b))
        if norm_a == 0 or norm_b == 0:
            return 0.0
        return dot / (norm_a * norm_b)

    sims: list[float] = []
    n = len(embeddings)
    for i in range(n):
        for j in range(i + 1, n):
            sims.append(cosine_sim(embeddings[i], embeddings[j]))

    if not sims:
        return 0.0, 0.0

    mean = sum(sims) / len(sims)
    variance = sum((s - mean) ** 2 for s in sims) / len(sims)
    std = math.sqrt(variance)
    return mean, std
