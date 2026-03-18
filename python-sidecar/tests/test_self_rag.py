"""Tests for PY-014: Self-RAG Automatic Improvement Loop."""

import sys
from unittest.mock import MagicMock, patch

import pytest

# Pre-mock heavy dependencies so self_rag can be imported
_mock_searcher = MagicMock()
_mock_embedder = MagicMock()
_mock_embedder.embed_single = MagicMock(return_value=[0.0] * 768)
sys.modules.setdefault("rag_pipeline.db", MagicMock())
sys.modules.setdefault("rank_bm25", MagicMock())
sys.modules.setdefault("rag_pipeline.tools.embedder", _mock_embedder)
sys.modules.setdefault("rag_pipeline.tools.searcher", _mock_searcher)

# Ensure query_transformer is importable (it imports embedder)
from rag_pipeline.tools import query_transformer  # noqa: E402

from rag_pipeline.tools.self_rag import self_rag_search, _evaluate_rag_heuristic  # noqa: E402


class TestSelfRagSearch:
    """self_rag_search 기본 동작 테스트."""

    def setup_method(self):
        """각 테스트 전 mock 초기화."""
        _mock_searcher.search_hybrid = MagicMock(return_value=[])

    def test_returns_results_on_first_iteration_high_score(self):
        """min_score를 넘으면 1회 반복으로 종료."""
        high_quality_results = [
            {"content": "문서 A 내용", "score": 0.9},
            {"content": "문서 B는 더 긴 내용을 담고 있습니다", "score": 0.85},
            {"content": "문서 C 중간 길이", "score": 0.8},
        ]

        _mock_searcher.search_hybrid.return_value = high_quality_results
        result = self_rag_search("테스트 쿼리", "src-1", min_score=0.3)

        assert result["iterations"] == 1
        assert result["final_score"] > 0.3
        assert result["queries_used"] == ["테스트 쿼리"]
        assert len(result["results"]) == 3

    def test_iterates_when_score_below_min(self):
        """점수가 낮으면 쿼리 변환 후 재검색."""
        low_results = [{"content": "짧음", "score": 0.1}]
        high_results = [
            {"content": "좋은 문서 내용", "score": 0.9},
            {"content": "또 다른 좋은 문서 내용입니다", "score": 0.85},
            {"content": "세 번째 문서", "score": 0.8},
        ]

        call_count = 0

        def mock_search(query, source_id, top_k):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return low_results
            return high_results

        _mock_searcher.search_hybrid.side_effect = mock_search

        transform_result = {
            "original_query": "테스트",
            "transformed_queries": ["테스트", "테스트 관련 정보", "테스트 설명"],
            "strategy_used": "multi_query",
            "metadata": {"variation_count": 2},
        }

        with patch.object(
            query_transformer, "transform_query", return_value=transform_result,
        ):
            result = self_rag_search("테스트", "src-1", min_score=0.5, max_iterations=2)

        assert result["iterations"] == 2
        assert len(result["queries_used"]) >= 2
        assert result["final_score"] > 0.0

    def test_max_iterations_respected(self):
        """max_iterations 초과하지 않음."""
        low_results = [{"content": "짧음", "score": 0.05}]
        _mock_searcher.search_hybrid.return_value = low_results

        transform_result = {
            "original_query": "q",
            "transformed_queries": ["q", "q 관련 정보", "q 설명"],
            "strategy_used": "multi_query",
            "metadata": {},
        }

        with patch.object(
            query_transformer, "transform_query", return_value=transform_result,
        ):
            result = self_rag_search("q", "src-1", min_score=0.99, max_iterations=3)

        assert result["iterations"] <= 3

    def test_empty_results_score_zero(self):
        """결과가 없으면 점수 0."""
        _mock_searcher.search_hybrid.return_value = []

        result = self_rag_search("빈 쿼리", "src-1", min_score=0.0, max_iterations=1)

        assert result["final_score"] == 0.0


class TestSelfRagImportErrors:
    """import 에러 처리 테스트."""

    def setup_method(self):
        _mock_searcher.search_hybrid = MagicMock(return_value=[])

    def test_query_transformer_unavailable_stops_iteration(self):
        """query_transformer 없으면 반복 중단."""
        low_results = [{"content": "짧음", "score": 0.05}]
        _mock_searcher.search_hybrid.return_value = low_results

        with patch.object(
            query_transformer, "transform_query",
            side_effect=ImportError("no transformer"),
        ):
            result = self_rag_search("q", "src-1", min_score=0.99, max_iterations=3)

        # Should stop after first iteration since transformer is unavailable
        assert result["iterations"] == 1


class TestEvaluateRagHeuristic:
    """_evaluate_rag_heuristic 점수 계산 테스트."""

    def test_high_quality_results(self):
        results = [
            {"content": "A" * 100, "score": 0.95},
            {"content": "B" * 200, "score": 0.90},
            {"content": "C" * 150, "score": 0.88},
        ]
        score = _evaluate_rag_heuristic(results)
        assert score > 0.5

    def test_empty_results(self):
        score = _evaluate_rag_heuristic([])
        assert score == 0.0

    def test_single_result_low_score(self):
        results = [{"content": "짧음", "score": 0.1}]
        score = _evaluate_rag_heuristic(results)
        assert score < 0.5

    def test_score_between_zero_and_one(self):
        results = [
            {"content": "text " * 20, "score": 0.5},
            {"content": "other text " * 10, "score": 0.6},
        ]
        score = _evaluate_rag_heuristic(results)
        assert 0.0 <= score <= 1.0
