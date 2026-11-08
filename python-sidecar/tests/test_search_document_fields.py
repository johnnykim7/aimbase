"""CR-170: search results carry the document ID and the vector similarity."""

import pytest

# 모듈 머리에서 불러 둔다. test_self_rag 는 수집할 때 searcher 가 아직 없으면 가짜를 끼운다
from rag_pipeline.tools import reranker, searcher


class _FakeCrossEncoder:
    def predict(self, pairs):
        return [float(len(doc)) for _, doc in pairs]


class TestSearchDocumentFields:

    def test_hybrid_search_carries_document_id_and_vector_score(self, monkeypatch):
        """TC-PY-RAG-013"""
        monkeypatch.setattr(searcher, "embed_single", lambda query, model="": [0.0])
        monkeypatch.setattr(searcher, "vector_search", lambda source_id, vec, top_k=5: [
            {"content": "반품 접수 오류", "metadata": {}, "vector_score": 0.82, "document_id": "Issue_1"},
        ])
        monkeypatch.setattr(searcher, "keyword_search_contents", lambda source_id: [
            {"content": "반품 접수 오류", "metadata": {}, "document_id": "Issue_1"},
            {"content": "반품 화면 오류 문구", "metadata": {}, "document_id": "Issue_2"},
            {"content": "배송 일정 안내", "metadata": {}, "document_id": "Issue_3"},
        ])

        results = searcher.search_hybrid("반품 오류", "src-a", top_k=5)
        by_doc = {r["document_id"]: r for r in results}

        assert by_doc["Issue_1"]["vector_score"] == pytest.approx(0.82)
        assert by_doc["Issue_2"]["vector_score"] == 0.0

    def test_rerank_keeps_search_fields(self, monkeypatch):
        """TC-PY-RAG-014"""
        monkeypatch.setattr(reranker, "_get_reranker", lambda model_name="": _FakeCrossEncoder())
        docs = [
            {"content": "짧은 글", "metadata": {"k": 1}, "document_id": "Issue_1",
             "vector_score": 0.4, "combined_score": 0.01},
            {"content": "조금 더 긴 글입니다", "metadata": {}, "document_id": "Issue_2",
             "vector_score": 0.7, "combined_score": 0.02},
        ]

        results = reranker.rerank_results("질문", docs, top_k=2)

        assert [r["document_id"] for r in results] == ["Issue_2", "Issue_1"]
        assert results[0]["vector_score"] == 0.7
        assert results[1]["metadata"] == {"k": 1}
        assert "rerank_score" in results[0]
