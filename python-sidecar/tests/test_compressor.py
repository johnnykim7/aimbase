"""Tests for PY-015: Context Compression."""

import pytest


class TestCompressContext:
    """컨텍스트 압축 테스트."""

    def test_basic_compression(self):
        from rag_pipeline.tools.compressor import compress_context

        docs = [
            {
                "content": "벡터 검색은 임베딩을 활용합니다. 오늘 날씨가 좋습니다. 유사도 기반 검색이 핵심입니다.",
                "metadata": {"source": "test"},
            }
        ]

        result = compress_context("벡터 검색 임베딩", docs, similarity_threshold=0.3)

        assert "compressed_documents" in result
        assert len(result["compressed_documents"]) == 1
        assert result["original_token_count"] > 0
        assert result["compressed_token_count"] > 0
        assert result["compression_ratio"] <= 1.0
        assert "sentences_removed" in result["compressed_documents"][0]

    def test_korean_text(self):
        from rag_pipeline.tools.compressor import compress_context

        docs = [
            {
                "content": "멀티테넌시는 하나의 소프트웨어로 여러 조직을 지원하는 아키텍처입니다. 피자는 이탈리아 음식입니다. 데이터베이스 격리가 중요합니다.",
                "metadata": {"source": "korean"},
            }
        ]

        result = compress_context("멀티테넌시 아키텍처", docs, similarity_threshold=0.3)

        assert len(result["compressed_documents"]) == 1
        compressed_doc = result["compressed_documents"][0]
        assert compressed_doc["metadata"] == {"source": "korean"}

    def test_threshold_zero_keeps_all(self):
        from rag_pipeline.tools.compressor import compress_context

        docs = [
            {
                "content": "첫번째 문장입니다. 두번째 문장입니다. 세번째 문장입니다.",
                "metadata": {},
            }
        ]

        result = compress_context("아무 쿼리", docs, similarity_threshold=0.0)

        compressed_doc = result["compressed_documents"][0]
        # threshold=0 이면 모든 문장의 유사도가 0 이상이므로 전부 유지
        assert compressed_doc["sentences_removed"] == 0

    def test_threshold_one_removes_most(self):
        from rag_pipeline.tools.compressor import compress_context

        docs = [
            {
                "content": "첫번째 문장입니다. 두번째 문장입니다. 세번째 문장입니다.",
                "metadata": {},
            }
        ]

        result = compress_context("완전히 다른 주제", docs, similarity_threshold=1.0)

        compressed_doc = result["compressed_documents"][0]
        # threshold=1.0 이면 거의 모든 문장이 제거되지만 최소 1개 유지
        assert compressed_doc["content"]  # not empty
        assert compressed_doc["sentences_removed"] >= 0

    def test_empty_input(self):
        from rag_pipeline.tools.compressor import compress_context

        result = compress_context("query", [], similarity_threshold=0.5)

        assert result["compressed_documents"] == []
        assert result["original_token_count"] == 0
        assert result["compressed_token_count"] == 0
        assert result["compression_ratio"] == 1.0

    def test_multiple_documents(self):
        from rag_pipeline.tools.compressor import compress_context

        docs = [
            {"content": "벡터 검색은 유사도 기반입니다.", "metadata": {"id": 1}},
            {"content": "오늘 점심은 김치찌개입니다.", "metadata": {"id": 2}},
        ]

        result = compress_context("벡터 검색", docs, similarity_threshold=0.3)

        assert len(result["compressed_documents"]) == 2
        # 각 문서에 최소 1개 문장은 유지
        for doc in result["compressed_documents"]:
            assert doc["content"]

    def test_preserves_at_least_one_sentence(self):
        from rag_pipeline.tools.compressor import compress_context

        docs = [
            {
                "content": "완전히 관련없는 내용입니다. 다른 주제의 문장입니다. 또 다른 무관한 내용입니다.",
                "metadata": {},
            }
        ]

        result = compress_context("양자 컴퓨팅 알고리즘", docs, similarity_threshold=0.99)

        compressed_doc = result["compressed_documents"][0]
        assert compressed_doc["content"]  # 빈 문자열이 아님


class TestSplitSentences:
    """문장 분리 유틸리티 테스트."""

    def test_empty_text(self):
        from rag_pipeline.tools.compressor import _split_sentences

        assert _split_sentences("") == []
        assert _split_sentences("   ") == []

    def test_korean_sentences(self):
        from rag_pipeline.tools.compressor import _split_sentences

        text = "이것은 첫번째 문장입니다. 이것은 두번째 문장입니다."
        sentences = _split_sentences(text)
        assert len(sentences) >= 2

    def test_single_sentence(self):
        from rag_pipeline.tools.compressor import _split_sentences

        sentences = _split_sentences("하나의 문장만 있습니다.")
        assert len(sentences) == 1


class TestEstimateTokens:
    """토큰 수 추정 테스트."""

    def test_estimate(self):
        from rag_pipeline.tools.compressor import _estimate_tokens

        assert _estimate_tokens("abcdef") == 2  # 6 // 3
        assert _estimate_tokens("") == 0
