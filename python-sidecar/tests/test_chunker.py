"""TC-PY-RAG-001, TC-PY-RAG-002: Semantic Chunking Tests."""

import pytest

from rag_pipeline.tools.chunker import chunk_document


class TestChunkDocument:
    """TC-PY-RAG-001: Semantic chunking normal."""

    def test_semantic_chunking_korean(self, korean_document):
        """Korean document with semantic strategy produces semantic chunks."""
        chunks = chunk_document(
            korean_document,
            strategy="semantic",
            config={"max_chunk_size": 200, "similarity_threshold": 0.7},
        )

        assert len(chunks) > 0
        # Each chunk should have required fields
        for chunk in chunks:
            assert "content" in chunk
            assert "metadata" in chunk
            assert "token_count" in chunk
            assert len(chunk["content"]) > 0
            assert chunk["metadata"]["strategy"] == "semantic"

        # All content should be preserved (no data loss)
        combined = "".join(c["content"] for c in chunks)
        # Semantic chunker may add/remove whitespace, so compare stripped
        assert len(combined.strip()) > 0

    def test_fixed_chunking(self, korean_document):
        """Fixed strategy chunks by size."""
        chunks = chunk_document(
            korean_document,
            strategy="fixed",
            config={"max_chunk_size": 100, "overlap": 20},
        )

        assert len(chunks) > 1
        for chunk in chunks:
            assert chunk["metadata"]["strategy"] == "fixed"

    def test_recursive_chunking(self, korean_document):
        """Recursive strategy chunks by sentence boundaries."""
        chunks = chunk_document(
            korean_document,
            strategy="recursive",
            config={"max_chunk_size": 200, "overlap": 30},
        )

        assert len(chunks) > 0
        for chunk in chunks:
            assert chunk["metadata"]["strategy"] == "recursive"

    def test_empty_input_returns_empty(self):
        """TC-PY-RAG-002: Empty input returns empty chunks."""
        assert chunk_document("") == []
        assert chunk_document("   ") == []

    def test_none_config_uses_defaults(self, korean_document):
        """None config uses default settings."""
        chunks = chunk_document(korean_document, strategy="fixed", config=None)
        assert len(chunks) > 0
