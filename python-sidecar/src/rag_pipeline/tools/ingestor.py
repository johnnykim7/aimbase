"""Ingest Document MCP Tool.

Orchestrates the full pipeline: parse → chunk → embed → store in pgvector.
"""

import logging

from rag_pipeline.db import delete_embeddings_by_source, store_embeddings
from rag_pipeline.tools.chunker import chunk_document
from rag_pipeline.tools.embedder import embed_texts

logger = logging.getLogger(__name__)

BATCH_SIZE = 20


def ingest_document(
    source_id: str,
    content: str,
    document_id: str = "",
    chunking_strategy: str = "semantic",
    chunking_config: dict | None = None,
    embedding_model: str = "",
) -> dict:
    """Full ingestion pipeline: chunk → embed → store.

    Args:
        source_id: Knowledge source ID
        content: Raw document text
        document_id: Optional document identifier
        chunking_strategy: "semantic" | "recursive" | "fixed"
        chunking_config: Chunking parameters
        embedding_model: Embedding model name

    Returns:
        {source_id, document_id, chunks_created, success, errors}
    """
    errors: list[dict] = []
    doc_id = document_id or source_id

    try:
        # 1) Delete existing embeddings for re-sync
        deleted = delete_embeddings_by_source(source_id)
        if deleted > 0:
            logger.info("Deleted %d existing embeddings for source '%s'", deleted, source_id)

        # 2) Chunk
        chunks = chunk_document(content, chunking_strategy, chunking_config)
        if not chunks:
            return {
                "source_id": source_id,
                "document_id": doc_id,
                "chunks_created": 0,
                "success": True,
                "errors": [],
            }

        logger.info("Created %d chunks for source '%s'", len(chunks), source_id)

        # 3) Embed + Store in batches
        total_stored = 0
        for i in range(0, len(chunks), BATCH_SIZE):
            batch = chunks[i : i + BATCH_SIZE]
            texts = [c["content"] for c in batch]

            try:
                result = embed_texts(texts, embedding_model)
                vectors = result["embeddings"]
                stored = store_embeddings(source_id, batch, vectors, doc_id)
                total_stored += stored
            except Exception as e:
                logger.warning("Batch %d failed: %s", i // BATCH_SIZE, str(e))
                errors.append({"batch": i // BATCH_SIZE, "error": str(e)})

        return {
            "source_id": source_id,
            "document_id": doc_id,
            "chunks_created": total_stored,
            "success": len(errors) == 0,
            "errors": errors,
        }

    except Exception as e:
        logger.error("Ingestion failed for source '%s': %s", source_id, str(e))
        return {
            "source_id": source_id,
            "document_id": doc_id,
            "chunks_created": 0,
            "success": False,
            "errors": [{"error": str(e)}],
        }
