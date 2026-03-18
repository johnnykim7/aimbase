"""Tests for PY-021: Embedding Drift Detection."""

import pytest
from unittest.mock import patch, MagicMock


class TestDetectEmbeddingDrift:
    """임베딩 드리프트 감지 테스트."""

    def _make_embeddings(self, n: int, dim: int = 4) -> list[list[float]]:
        """테스트용 임베딩 생성."""
        import random
        random.seed(42)
        return [[random.gauss(0, 1) for _ in range(dim)] for _ in range(n)]

    @patch("evaluation.drift_detector._fetch_embeddings")
    def test_basic_drift_detection(self, mock_fetch):
        """기본 드리프트 감지 (baseline 없음 → drift_score=0)."""
        from evaluation.drift_detector import detect_embedding_drift

        mock_fetch.return_value = self._make_embeddings(10)

        result = detect_embedding_drift("source-1", sample_size=10)

        assert result["drift_score"] == 0.0
        assert "mean_similarity" in result
        assert "std_similarity" in result
        assert result["sample_size"] == 10
        assert result["recommendation"] == "stable"

    @patch("evaluation.drift_detector._fetch_embeddings")
    def test_drift_with_baseline(self, mock_fetch):
        """baseline 대비 드리프트 계산."""
        from evaluation.drift_detector import detect_embedding_drift

        mock_fetch.return_value = self._make_embeddings(10)

        baseline = {"mean": 0.9, "std": 0.1}
        result = detect_embedding_drift("source-1", sample_size=10, baseline_stats=baseline)

        assert result["drift_score"] > 0
        assert result["recommendation"] in ("stable", "reindex_recommended")

    @patch("evaluation.drift_detector._fetch_embeddings")
    def test_high_drift_recommends_reindex(self, mock_fetch):
        """drift_score > 0.3 → reindex_recommended."""
        from evaluation.drift_detector import detect_embedding_drift

        mock_fetch.return_value = self._make_embeddings(10)

        # 현재 mean과 크게 다른 baseline 설정
        baseline = {"mean": 10.0, "std": 0.01}
        result = detect_embedding_drift("source-1", sample_size=10, baseline_stats=baseline)

        assert result["drift_score"] > 0.3
        assert result["recommendation"] == "reindex_recommended"

    @patch("evaluation.drift_detector._fetch_embeddings")
    def test_insufficient_embeddings(self, mock_fetch):
        """임베딩 1개 이하 → insufficient_data."""
        from evaluation.drift_detector import detect_embedding_drift

        mock_fetch.return_value = [[0.1, 0.2]]

        result = detect_embedding_drift("source-1", sample_size=10)

        assert result["recommendation"] == "insufficient_data"
        assert result["sample_size"] == 1

    @patch("evaluation.drift_detector._fetch_embeddings")
    def test_empty_embeddings(self, mock_fetch):
        """임베딩 없음 → insufficient_data."""
        from evaluation.drift_detector import detect_embedding_drift

        mock_fetch.return_value = []

        result = detect_embedding_drift("source-1", sample_size=10)

        assert result["recommendation"] == "insufficient_data"
        assert result["sample_size"] == 0

    @patch("evaluation.drift_detector._fetch_embeddings")
    def test_db_connection_error(self, mock_fetch):
        """DB 연결 실패 → error 응답."""
        from evaluation.drift_detector import detect_embedding_drift

        mock_fetch.side_effect = Exception("Connection refused")

        result = detect_embedding_drift("source-1")

        assert "error" in result
        assert result["recommendation"] == "error"
        assert result["drift_score"] == 0.0

    @patch("evaluation.drift_detector._fetch_embeddings")
    def test_result_structure(self, mock_fetch):
        """결과 구조 검증."""
        from evaluation.drift_detector import detect_embedding_drift

        mock_fetch.return_value = self._make_embeddings(5)

        result = detect_embedding_drift("source-1", sample_size=5)

        expected_keys = {"drift_score", "mean_similarity", "std_similarity", "sample_size", "recommendation"}
        assert expected_keys.issubset(set(result.keys()))


class TestComputeStatsPure:
    """순수 Python 통계 계산 테스트."""

    def test_identical_vectors(self):
        from evaluation.drift_detector import _compute_stats_pure

        embeddings = [[1.0, 0.0], [1.0, 0.0], [1.0, 0.0]]
        mean, std = _compute_stats_pure(embeddings)

        assert abs(mean - 1.0) < 0.01
        assert std < 0.01

    def test_orthogonal_vectors(self):
        from evaluation.drift_detector import _compute_stats_pure

        embeddings = [[1.0, 0.0], [0.0, 1.0]]
        mean, std = _compute_stats_pure(embeddings)

        assert abs(mean) < 0.01  # cos similarity = 0

    def test_empty_embeddings(self):
        from evaluation.drift_detector import _compute_stats_pure

        mean, std = _compute_stats_pure([])
        assert mean == 0.0
        assert std == 0.0
