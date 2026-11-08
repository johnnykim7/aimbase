"""TC-PY-RAG-006: Ingestion Pipeline Integration Test.

Note: Requires running PostgreSQL with pgvector.
"""

import pytest


@pytest.mark.skipif(
    True,  # Skip by default; set to False when DB is available
    reason="Requires running PostgreSQL with pgvector",
)
class TestIngestDocumentIntegration:
    """TC-PY-RAG-006: Full ingestion pipeline test."""

    def test_ingest_document_full_pipeline(self, korean_document):
        """Ingest document: chunk → embed → store in pgvector."""
        from rag_pipeline.tools.ingestor import ingest_document

        result = ingest_document(
            source_id="test-ingest-source",
            content=korean_document,
            document_id="test-doc-001",
            chunking_strategy="fixed",
            chunking_config={"max_chunk_size": 200, "overlap": 30},
        )

        assert result["success"] is True
        assert result["chunks_created"] > 0
        assert result["source_id"] == "test-ingest-source"
        assert result["errors"] == []

    def test_ingest_empty_content(self):
        """Empty content produces zero chunks without error."""
        from rag_pipeline.tools.ingestor import ingest_document

        result = ingest_document(
            source_id="test-empty",
            content="",
        )

        assert result["success"] is True
        assert result["chunks_created"] == 0


class _FakeEmbeddings:
    """In-memory stand-in for the embeddings table: (source_id, document_id, content) rows."""

    def __init__(self):
        self.rows: list[tuple[str, str, str]] = []

    def store(self, source_id, chunks, vectors, document_id=""):
        for chunk in chunks:
            self.rows.append((source_id, document_id or source_id, chunk["content"]))
        return len(chunks)

    def delete_source(self, source_id):
        before = len(self.rows)
        self.rows = [r for r in self.rows if r[0] != source_id]
        return before - len(self.rows)

    def delete_document(self, source_id, document_id):
        before = len(self.rows)
        self.rows = [r for r in self.rows if not (r[0] == source_id and r[1] == document_id)]
        return before - len(self.rows)

    def contents(self, source_id):
        return sorted((r[1], r[2]) for r in self.rows if r[0] == source_id)


@pytest.fixture
def fake_embeddings(monkeypatch):
    from rag_pipeline.tools import ingestor

    fake = _FakeEmbeddings()
    monkeypatch.setattr(ingestor, "store_embeddings", fake.store)
    monkeypatch.setattr(ingestor, "delete_embeddings_by_source", fake.delete_source)
    monkeypatch.setattr(ingestor, "delete_embeddings_by_document", fake.delete_document)
    monkeypatch.setattr(ingestor, "chunk_document",
                        lambda content, strategy, config: [{"content": content, "metadata": {}}])
    monkeypatch.setattr(ingestor, "embed_texts",
                        lambda texts, model="": {"embeddings": [[0.0] for _ in texts]})
    return fake


class TestIngestReplaceScope:
    """CR-170: one ingest call replaces only what it names."""

    def test_two_documents_in_one_source_both_remain(self, fake_embeddings):
        """TC-PY-RAG-008"""
        from rag_pipeline.tools.ingestor import ingest_document

        assert ingest_document("src-a", "issue one", document_id="Issue_1")["success"] is True
        assert ingest_document("src-a", "issue two", document_id="Issue_2")["success"] is True

        assert fake_embeddings.contents("src-a") == [("Issue_1", "issue one"), ("Issue_2", "issue two")]

    def test_same_document_id_replaces_only_that_document(self, fake_embeddings):
        """TC-PY-RAG-009"""
        from rag_pipeline.tools.ingestor import ingest_document

        ingest_document("src-a", "issue one", document_id="Issue_1")
        ingest_document("src-a", "issue two", document_id="Issue_2")
        ingest_document("src-b", "other source", document_id="Issue_1")

        result = ingest_document("src-a", "issue one resolved", document_id="Issue_1")

        assert result["document_id"] == "Issue_1"
        assert fake_embeddings.contents("src-a") == [
            ("Issue_1", "issue one resolved"),
            ("Issue_2", "issue two"),
        ]
        assert fake_embeddings.contents("src-b") == [("Issue_1", "other source")]

    def test_without_document_id_replaces_whole_source(self, fake_embeddings):
        """TC-PY-RAG-010"""
        from rag_pipeline.tools.ingestor import ingest_document

        ingest_document("src-a", "issue one", document_id="Issue_1")
        ingest_document("src-a", "issue two", document_id="Issue_2")

        result = ingest_document("src-a", "whole text")

        assert result["document_id"] == "src-a"
        assert fake_embeddings.contents("src-a") == [("src-a", "whole text")]

    def test_ingest_file_replaces_whole_source(self, fake_embeddings, tmp_path):
        """TC-PY-RAG-011"""
        from rag_pipeline.tools.ingestor import ingest_document, ingest_file

        ingest_document("src-f", "old file", document_id="old/report.txt")
        (tmp_path / "new").mkdir()
        (tmp_path / "new" / "report.txt").write_text("new file", encoding="utf-8")

        result = ingest_file("src-f", "new/report.txt", str(tmp_path), chunking_strategy="fixed")

        assert result["success"] is True
        assert fake_embeddings.contents("src-f") == [("new/report.txt", "new file")]

    def test_ingest_file_unreadable_keeps_existing(self, fake_embeddings, tmp_path):
        """TC-PY-RAG-012"""
        from rag_pipeline.tools.ingestor import ingest_document, ingest_file

        ingest_document("src-f", "old file", document_id="old/report.txt")

        result = ingest_file("src-f", "missing/report.txt", str(tmp_path))

        assert result["success"] is False
        assert fake_embeddings.contents("src-f") == [("old/report.txt", "old file")]
