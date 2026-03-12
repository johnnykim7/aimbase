"""Aimbase RAG Pipeline - FastMCP Server.

MCP Server 1: RAG Pipeline
Tools: ingest_document, search_hybrid, embed_texts, chunk_document, rerank_results
"""

import json
import logging

from fastmcp import FastMCP

from rag_pipeline.config import settings

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

mcp = FastMCP(
    "Aimbase RAG Pipeline",
    description="RAG pipeline tools: chunking, embedding, hybrid search, reranking",
)


@mcp.tool()
def chunk_document(
    content: str,
    strategy: str = "semantic",
    config: str = "{}",
) -> str:
    """Split document into semantic chunks.

    Supports strategies: semantic (LlamaIndex), recursive, fixed.
    Korean sentence segmentation supported.

    Args:
        content: Raw document text to chunk
        strategy: Chunking strategy - "semantic", "recursive", or "fixed"
        config: JSON string with {max_chunk_size, similarity_threshold, overlap}
    """
    from rag_pipeline.tools.chunker import chunk_document as do_chunk

    cfg = json.loads(config) if isinstance(config, str) else config
    chunks = do_chunk(content, strategy, cfg)
    return json.dumps({"chunks": chunks}, ensure_ascii=False)


@mcp.tool()
def embed_texts(
    texts: list[str],
    model: str = "",
) -> str:
    """Generate embedding vectors for text array.

    Default model: KoSimCSE (Korean-specialized, 768 dimensions).

    Args:
        texts: List of text strings to embed
        model: Model name (default: KoSimCSE-roberta-multitask)
    """
    from rag_pipeline.tools.embedder import embed_texts as do_embed

    result = do_embed(texts, model)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def search_hybrid(
    query: str,
    source_id: str,
    top_k: int = 5,
    vector_weight: float = 0.7,
    keyword_weight: float = 0.3,
) -> str:
    """Hybrid search combining BM25 keyword and vector semantic search.

    Uses Reciprocal Rank Fusion (RRF) to combine scores.

    Args:
        query: Search query text
        source_id: Knowledge source ID to search in
        top_k: Number of results to return
        vector_weight: Weight for vector search (0.0-1.0)
        keyword_weight: Weight for BM25 keyword search (0.0-1.0)
    """
    from rag_pipeline.tools.searcher import search_hybrid as do_search

    results = do_search(query, source_id, top_k, vector_weight, keyword_weight)
    return json.dumps({"query": query, "results": results}, ensure_ascii=False)


@mcp.tool()
def rerank_results(
    query: str,
    documents: str,
    top_k: int = 5,
    model: str = "",
) -> str:
    """Re-rank search results using cross-encoder for improved accuracy.

    Default model: cross-encoder/ms-marco-MiniLM-L-6-v2.

    Args:
        query: Original query text
        documents: JSON string of [{content, metadata}]
        top_k: Number of top results to return
        model: Cross-encoder model name
    """
    from rag_pipeline.tools.reranker import rerank_results as do_rerank

    docs = json.loads(documents) if isinstance(documents, str) else documents
    results = do_rerank(query, docs, top_k, model)
    return json.dumps({"results": results}, ensure_ascii=False)


@mcp.tool()
def ingest_document(
    source_id: str,
    content: str,
    document_id: str = "",
    chunking_strategy: str = "semantic",
    chunking_config: str = "{}",
    embedding_model: str = "",
) -> str:
    """Full ingestion pipeline: parse → chunk → embed → store in pgvector.

    Processes document through semantic chunking, generates embeddings,
    and stores them in PostgreSQL with pgvector.

    Args:
        source_id: Knowledge source ID
        content: Raw document text to ingest
        document_id: Optional document identifier
        chunking_strategy: "semantic", "recursive", or "fixed"
        chunking_config: JSON string with chunking parameters
        embedding_model: Embedding model name (default: KoSimCSE)
    """
    from rag_pipeline.tools.ingestor import ingest_document as do_ingest

    cfg = json.loads(chunking_config) if isinstance(chunking_config, str) else chunking_config
    result = do_ingest(
        source_id, content, document_id, chunking_strategy, cfg, embedding_model
    )
    return json.dumps(result, ensure_ascii=False)


def main():
    """Start the MCP server with SSE transport."""
    logger.info(
        "Starting Aimbase RAG Pipeline MCP Server on %s:%d",
        settings.MCP_HOST,
        settings.MCP_PORT,
    )
    mcp.run(transport="sse", host=settings.MCP_HOST, port=settings.MCP_PORT)


if __name__ == "__main__":
    main()
