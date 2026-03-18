"""Tests for PY-012: Embedding Fine-tuning Pipeline."""

import pytest


class TestFinetuneEmbeddingModel:
    """파인튜닝 파이프라인 테스트."""

    def test_empty_training_data_returns_error(self):
        from rag_pipeline.tools.finetuner import finetune_embedding_model

        result = finetune_embedding_model("test-model", [], epochs=1)

        assert result["success"] is False
        assert "No training data" in result["error"]

    def test_insufficient_data_returns_error(self):
        from rag_pipeline.tools.finetuner import finetune_embedding_model

        result = finetune_embedding_model(
            "test-model",
            [{"query": "q1", "positive": "p1"}],
            epochs=1,
        )

        assert result["success"] is False
        assert "Insufficient" in result["error"]

    def test_invalid_training_items_skipped(self):
        from rag_pipeline.tools.finetuner import finetune_embedding_model

        result = finetune_embedding_model(
            "test-model",
            [
                {"query": "q1", "positive": "p1"},
                {"bad_key": "no query"},  # invalid — skipped
                {"query": "q2", "positive": "p2"},
            ],
            epochs=1,
        )

        # 유효 데이터 2건으로 진행됨 (simulated 포함)
        assert result["success"] is True
        assert result["metrics"]["training_samples"] == 2

    def test_simulated_training_returns_result(self):
        from rag_pipeline.tools.finetuner import finetune_embedding_model

        result = finetune_embedding_model(
            "nonexistent-model",
            [
                {"query": "쿼리1", "positive": "긍정 문서1"},
                {"query": "쿼리2", "positive": "긍정 문서2", "negative": "부정 문서2"},
            ],
            epochs=2,
            batch_size=8,
        )

        # sentence-transformers가 모델 로드에 실패하면 simulate fallback
        assert result["success"] is True
        assert result["model_path"]
        assert result["metrics"]["epochs"] == 2


class TestGenerateTrainingPairs:
    """검색 로그로부터 학습 데이터 생성 테스트."""

    def test_generates_pairs_from_logs(self):
        from rag_pipeline.tools.finetuner import generate_training_pairs

        logs = [
            {
                "query": "멀티테넌시란?",
                "results": [
                    {"content": "멀티테넌시는 하나의 소프트웨어...", "score": 0.9},
                    {"content": "관련없는 문서", "score": 0.3},
                ],
            }
        ]

        pairs = generate_training_pairs(logs, min_relevance_score=0.7)

        assert len(pairs) == 1
        assert pairs[0]["query"] == "멀티테넌시란?"
        assert pairs[0]["positive"] == "멀티테넌시는 하나의 소프트웨어..."
        assert pairs[0]["negative"] == "관련없는 문서"

    def test_skips_logs_without_enough_results(self):
        from rag_pipeline.tools.finetuner import generate_training_pairs

        logs = [
            {"query": "test", "results": [{"content": "one", "score": 0.9}]},
        ]

        pairs = generate_training_pairs(logs)
        assert len(pairs) == 0

    def test_no_negative_when_all_relevant(self):
        from rag_pipeline.tools.finetuner import generate_training_pairs

        logs = [
            {
                "query": "벡터 검색",
                "results": [
                    {"content": "벡터 검색은...", "score": 0.9},
                    {"content": "유사도 검색...", "score": 0.8},
                ],
            }
        ]

        pairs = generate_training_pairs(logs, min_relevance_score=0.7)

        assert len(pairs) == 2
        assert "negative" not in pairs[0]
