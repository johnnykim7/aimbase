"""PY-014: Self-RAG Automatic Improvement Loop.

Iteratively refines RAG search by evaluating result quality
and transforming queries when results are insufficient.
"""

import logging
from typing import Any

logger = logging.getLogger(__name__)


def self_rag_search(
    query: str,
    source_id: str,
    top_k: int = 5,
    min_score: float = 0.5,
    max_iterations: int = 2,
) -> dict[str, Any]:
    """Self-RAG search with automatic query refinement.

    1. Search with the original query
    2. Evaluate result quality using heuristics
    3. If quality < min_score, transform query and re-search
    4. Repeat up to max_iterations

    Args:
        query: Search query text
        source_id: Knowledge source ID to search in
        top_k: Number of results to return
        min_score: Minimum quality score to accept results (0.0-1.0)
        max_iterations: Maximum refinement iterations

    Returns:
        {results, iterations, final_score, queries_used}
    """
    try:
        from rag_pipeline.tools.searcher import search_hybrid
    except ImportError as e:
        logger.warning("searcher module not available: %s", e)
        return {
            "error": "searcher module not available",
            "results": [],
            "iterations": 0,
            "final_score": 0.0,
            "queries_used": [query],
        }

    queries_used = [query]
    current_query = query
    best_results: list[dict] = []
    best_score = 0.0
    iterations = 0

    for iteration in range(max_iterations):
        iterations = iteration + 1

        # Search with current query
        results = search_hybrid(current_query, source_id, top_k)

        # Evaluate quality
        score = _evaluate_rag_heuristic(results)

        # Keep best results
        if score > best_score:
            best_score = score
            best_results = results

        # If quality is acceptable, stop
        if score >= min_score:
            break

        # If more iterations available, try query transformation
        if iteration < max_iterations - 1:
            try:
                from rag_pipeline.tools.query_transformer import transform_query

                transformed = transform_query(current_query, "multi_query")
                alternative_queries = transformed.get("transformed_queries", [])

                # Pick the best alternative (skip original)
                for alt_query in alternative_queries:
                    if alt_query != current_query and alt_query not in queries_used:
                        current_query = alt_query
                        queries_used.append(alt_query)
                        break
                else:
                    # No new alternative found, stop iterating
                    break

            except ImportError as e:
                logger.warning("query_transformer module not available: %s", e)
                break

    return {
        "results": best_results,
        "iterations": iterations,
        "final_score": best_score,
        "queries_used": queries_used,
    }


def _evaluate_rag_heuristic(results: list[dict]) -> float:
    """Evaluate RAG result quality using heuristics.

    Score based on:
    - Number of results (more results = better coverage)
    - Average similarity scores (higher = more relevant)
    - Content length diversity (varied lengths = diverse sources)

    Returns:
        Quality score between 0.0 and 1.0
    """
    if not results:
        return 0.0

    # Factor 1: Result count (0-1, scaled by expected count)
    count_score = min(len(results) / 3.0, 1.0)

    # Factor 2: Average similarity score
    scores = []
    for r in results:
        score = r.get("score") or r.get("similarity") or r.get("combined_score", 0.0)
        if isinstance(score, (int, float)):
            scores.append(float(score))

    avg_score = sum(scores) / len(scores) if scores else 0.0
    similarity_score = min(avg_score, 1.0)

    # Factor 3: Content length diversity (coefficient of variation)
    lengths = []
    for r in results:
        content = r.get("content", r.get("text", ""))
        if content:
            lengths.append(len(content))

    if len(lengths) >= 2:
        mean_len = sum(lengths) / len(lengths)
        if mean_len > 0:
            variance = sum((l - mean_len) ** 2 for l in lengths) / len(lengths)
            cv = (variance ** 0.5) / mean_len
            # Some diversity is good (cv 0.3-0.7), too much or too little is bad
            diversity_score = min(cv / 0.5, 1.0) if cv <= 1.0 else max(0.0, 2.0 - cv)
        else:
            diversity_score = 0.0
    elif len(lengths) == 1:
        diversity_score = 0.5  # Single result, neutral diversity
    else:
        diversity_score = 0.0

    # Weighted combination
    final_score = (
        count_score * 0.3
        + similarity_score * 0.5
        + diversity_score * 0.2
    )

    return round(final_score, 4)
