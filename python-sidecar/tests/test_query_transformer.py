"""Tests for PY-005: Query Transformation."""

import pytest


class TestTransformQueryMultiQuery:
    """multi_query 전략 테스트."""

    def test_returns_original_and_variations(self):
        from rag_pipeline.tools.query_transformer import transform_query

        result = transform_query("멀티테넌시 아키텍처란?", "multi_query")

        assert result["original_query"] == "멀티테넌시 아키텍처란?"
        assert result["strategy_used"] == "multi_query"
        assert len(result["transformed_queries"]) >= 2
        assert result["transformed_queries"][0] == "멀티테넌시 아키텍처란?"

    def test_variation_count_in_metadata(self):
        from rag_pipeline.tools.query_transformer import transform_query

        result = transform_query("RAG 검색 최적화", "multi_query")

        assert "variation_count" in result["metadata"]
        assert result["metadata"]["variation_count"] >= 1


class TestTransformQueryHyDE:
    """HyDE 전략 테스트."""

    def test_generates_hypothesis(self):
        from rag_pipeline.tools.query_transformer import transform_query

        result = transform_query("벡터 검색이 무엇인가요?", "hyde")

        assert result["strategy_used"] == "hyde"
        assert "hypothesis" in result["metadata"]
        assert len(result["transformed_queries"]) == 2
        # hypothesis_embedding is generated but may be removed in server layer
        assert result["metadata"]["hypothesis"]

    def test_question_type_detection(self):
        from rag_pipeline.tools.query_transformer import transform_query

        how_result = transform_query("어떻게 임베딩을 생성하나요?", "hyde")
        assert "방법" in how_result["metadata"]["hypothesis"] or "과정" in how_result["metadata"]["hypothesis"]

        why_result = transform_query("왜 하이브리드 검색이 필요한가요?", "hyde")
        assert "이유" in why_result["metadata"]["hypothesis"] or "원인" in why_result["metadata"]["hypothesis"]


class TestTransformQueryStepBack:
    """step_back 전략 테스트."""

    def test_generates_broader_query(self):
        from rag_pipeline.tools.query_transformer import transform_query

        result = transform_query("2024년 3분기 매출 데이터", "step_back")

        assert result["strategy_used"] == "step_back"
        assert len(result["transformed_queries"]) == 2
        assert "step_back_query" in result["metadata"]

    def test_removes_specific_details(self):
        from rag_pipeline.tools.query_transformer import transform_query

        result = transform_query("2024-01-15 서버 장애 원인", "step_back")
        step_back = result["metadata"]["step_back_query"]

        # 날짜가 제거된 더 일반적인 쿼리
        assert "2024-01-15" not in step_back


class TestTransformQueryEdgeCases:
    """엣지 케이스 테스트."""

    def test_invalid_strategy_raises_error(self):
        from rag_pipeline.tools.query_transformer import transform_query

        with pytest.raises(ValueError, match="Unknown strategy"):
            transform_query("test", "invalid_strategy")

    def test_empty_query(self):
        from rag_pipeline.tools.query_transformer import transform_query

        result = transform_query("", "multi_query")
        assert result["original_query"] == ""
        assert result["strategy_used"] == "multi_query"
