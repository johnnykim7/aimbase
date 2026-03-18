"""Aimbase RAG Pipeline - FastMCP Server.

MCP Server 1: RAG Pipeline
Tools: ingest_document, search_hybrid, embed_texts, chunk_document, rerank_results,
       transform_query, finetune_embeddings, parse_document, compress_context,
       embed_multimodal, scrape_url
"""

import json
import logging

from fastmcp import FastMCP

from rag_pipeline.config import settings

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

mcp = FastMCP("Aimbase RAG Pipeline")


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


@mcp.tool()
def transform_query(
    query: str,
    strategy: str = "multi_query",
    llm_config: str = "{}",
) -> str:
    """Transform user query for improved retrieval quality.

    Strategies:
    - hyde: Hypothetical Document Embedding - generates a hypothetical answer and uses it for retrieval
    - multi_query: Generates multiple diverse query variations for better recall
    - step_back: Creates a broader, more abstract version to capture general context

    Args:
        query: Original user query to transform
        strategy: Transformation strategy - "hyde", "multi_query", or "step_back"
        llm_config: JSON string with optional LLM config {model, connection_id}
    """
    from rag_pipeline.tools.query_transformer import transform_query as do_transform

    cfg = json.loads(llm_config) if isinstance(llm_config, str) else llm_config
    result = do_transform(query, strategy, cfg if cfg else None)

    # Remove embedding vectors from response (too large for JSON)
    metadata = result.get("metadata", {})
    metadata.pop("hypothesis_embedding", None)

    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def finetune_embeddings(
    base_model: str = "",
    training_data: str = "[]",
    epochs: int = 3,
    batch_size: int = 16,
    output_dir: str = "",
) -> str:
    """Fine-tune an embedding model with domain-specific training data (PY-012).

    Accepts (query, positive, negative) triplets from search logs and
    fine-tunes a sentence-transformers model for improved domain retrieval.

    Args:
        base_model: Base model name (default: KoSimCSE-roberta-multitask)
        training_data: JSON array of {query, positive, negative?} triplets
        epochs: Number of training epochs (default: 3)
        batch_size: Training batch size (default: 16)
        output_dir: Directory to save fine-tuned model (default: temp dir)
    """
    from rag_pipeline.tools.finetuner import finetune_embedding_model

    model_name = base_model or settings.DEFAULT_EMBED_MODEL
    data = json.loads(training_data) if isinstance(training_data, str) else training_data

    result = finetune_embedding_model(
        model_name, data, epochs, batch_size, output_dir,
    )
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def parse_document(
    file_content: str,
    file_type: str = "",
) -> str:
    """Parse a base64-encoded document file into plain text + metadata (PY-013).

    Supports: PDF, DOCX, PPTX, XLSX, CSV, HTML, TXT, Markdown.
    Input must be base64-encoded file bytes.

    Args:
        file_content: Base64-encoded file bytes
        file_type: File type hint (e.g. "pdf", "docx"). If empty, auto-detected.
    """
    from rag_pipeline.tools.parser import parse_document as do_parse

    result = do_parse(file_content, file_type)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def compress_context(
    query: str,
    documents: str,
    similarity_threshold: float = 0.5,
) -> str:
    """Compress context by removing sentences with low similarity to query (PY-015).

    Uses sentence-transformers cosine similarity to filter out irrelevant sentences.
    Preserves at least 1 sentence per document. Handles Korean sentence boundaries.

    Args:
        query: Search query to compare against
        documents: JSON array of [{content, metadata}] documents to compress
        similarity_threshold: Minimum cosine similarity to keep a sentence (0.0-1.0)
    """
    from rag_pipeline.tools.compressor import compress_context as do_compress

    docs = json.loads(documents) if isinstance(documents, str) else documents
    result = do_compress(query, docs, similarity_threshold)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def self_rag_search(
    query: str,
    source_id: str,
    top_k: int = 5,
    min_score: float = 0.5,
    max_iterations: int = 2,
) -> str:
    """Self-RAG search with automatic query refinement (PY-014).

    Iteratively refines RAG search by evaluating result quality
    and transforming queries when results are insufficient.

    1. Search with the original query
    2. Evaluate result quality using heuristics (count, similarity, diversity)
    3. If quality < min_score, transform query and re-search
    4. Repeat up to max_iterations

    Args:
        query: Search query text
        source_id: Knowledge source ID to search in
        top_k: Number of results to return
        min_score: Minimum quality score to accept results (0.0-1.0)
        max_iterations: Maximum refinement iterations
    """
    from rag_pipeline.tools.self_rag import self_rag_search as do_self_rag

    result = do_self_rag(query, source_id, top_k, min_score, max_iterations)
    return json.dumps(result, ensure_ascii=False, default=str)


@mcp.tool()
def embed_multimodal(
    items: str,
    model: str = "",
) -> str:
    """Generate multimodal embeddings using CLIP model (PY-016).

    Embeds text and images into the same vector space for cross-modal search.
    Text items use CLIP text encoder, image items use CLIP vision encoder.

    Args:
        items: JSON array of [{type: "text"|"image", content: str}].
               text content is plain string, image content is base64-encoded.
        model: CLIP model name (default: openai/clip-vit-base-patch32)
    """
    from rag_pipeline.tools.multimodal_embedder import embed_multimodal as do_embed

    parsed_items = json.loads(items) if isinstance(items, str) else items
    result = do_embed(parsed_items, model)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def scrape_url(
    url: str,
    mode: str = "basic",
    max_pages: int = 10,
    timeout_ms: int = 30000,
) -> str:
    """Scrape web page content with robots.txt compliance (PY-017).

    Modes:
    - basic: urllib + unstructured HTML text extraction
    - js_render: Playwright for JavaScript-rendered pages (optional dependency)
    - sitemap: Parse sitemap.xml and crawl pages up to max_pages

    Respects robots.txt and applies 1-second rate limiting between requests.

    Args:
        url: URL to scrape
        mode: Scraping mode - "basic", "js_render", or "sitemap"
        max_pages: Maximum pages to crawl in sitemap mode
        timeout_ms: Request timeout in milliseconds
    """
    from rag_pipeline.tools.scraper import scrape_url as do_scrape

    result = do_scrape(url, mode, max_pages, timeout_ms)
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
