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
