"""TC-PY-RAG-004: Hybrid Search Tests.

Note: These tests require a running PostgreSQL with pgvector.
They are integration tests and should be run with Docker Compose.
"""

import pytest

from rag_pipeline.tools.searcher import _rrf_score


class TestRRFScore:
    """Unit test for RRF scoring function."""

    def test_rrf_score_decreases_with_rank(self):
        """Higher rank (worse) gives lower RRF score."""
        score_0 = _rrf_score(0)
        score_1 = _rrf_score(1)
        score_5 = _rrf_score(5)

        assert score_0 > score_1 > score_5

    def test_rrf_score_positive(self):
        """RRF scores should always be positive."""
        for rank in range(100):
            assert _rrf_score(rank) > 0


@pytest.mark.skipif(
    True,  # Skip by default; set to False when DB is available
    reason="Requires running PostgreSQL with pgvector",
)
class TestSearchHybridIntegration:
    """TC-PY-RAG-004: Hybrid search integration test.

    Requires:
    - PostgreSQL with pgvector running
    - Embeddings data pre-loaded via ingest_document
    """

    def test_hybrid_search_returns_combined_scores(self):
        """Hybrid search returns results with vector, keyword, and combined scores."""
        from rag_pipeline.tools.searcher import search_hybrid

        results = search_hybrid(
            query="반품 정책",
            source_id="test-source",
            top_k=5,
        )

        for r in results:
            assert "content" in r
            assert "vector_score" in r
            assert "keyword_score" in r
            assert "combined_score" in r
