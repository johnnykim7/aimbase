"""TC-PY-RAG-005: Reranking Tests."""

from rag_pipeline.tools.reranker import rerank_results


class TestRerankResults:
    """TC-PY-RAG-005: Reranking with cross-encoder."""

    def test_rerank_sorts_by_relevance(self, sample_documents):
        """Results are re-sorted by rerank_score descending."""
        query = "반품 정책"
        results = rerank_results(query, sample_documents, top_k=5)

        assert len(results) == 5

        # Scores should be descending
        scores = [r["rerank_score"] for r in results]
        assert scores == sorted(scores, reverse=True)

        # Each result should have required fields
        for r in results:
            assert "content" in r
            assert "metadata" in r
            assert "rerank_score" in r

    def test_rerank_empty_documents(self):
        """Empty documents returns empty results."""
        results = rerank_results("test query", [], top_k=5)
        assert results == []

    def test_rerank_respects_top_k(self, sample_documents):
        """Returns at most top_k results."""
        results = rerank_results("반품", sample_documents, top_k=3)
        assert len(results) == 3

    def test_rerank_relevance_ordering(self, sample_documents):
        """Most relevant document should rank highest for specific query."""
        query = "반품 정책"
        results = rerank_results(query, sample_documents, top_k=10)

        # The top result should contain "반품" related content
        top_contents = [r["content"] for r in results[:3]]
        has_return_policy = any("반품" in c for c in top_contents)
        assert has_return_policy
