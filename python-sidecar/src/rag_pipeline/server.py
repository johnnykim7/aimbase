"""Aimbase RAG Pipeline - FastMCP Server.

MCP Server 1: RAG Pipeline
Tools: ingest_document, ingest_file, search_hybrid, embed_texts, chunk_document,
       rerank_results, transform_query, finetune_embeddings, parse_document,
       compress_context, embed_multimodal, scrape_url, contextual_chunk,
       parent_child_search, evaluate_rag, advanced_parse, cache_lookup, cache_store,
       generate_document, save/list/get/delete/render_document_template,
       upload_file_template, read_pptx, read_docx, read_excel, read_pdf, convert_format
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
    embedding_model: str = "",
) -> str:
    """Hybrid search combining BM25 keyword and vector semantic search.

    Uses Reciprocal Rank Fusion (RRF) to combine scores.

    Args:
        query: Search query text
        source_id: Knowledge source ID to search in
        top_k: Number of results to return
        vector_weight: Weight for vector search (0.0-1.0)
        keyword_weight: Weight for BM25 keyword search (0.0-1.0)
        embedding_model: 쿼리 임베딩에 사용할 모델 (빈 문자열이면 기본 모델)
    """
    from rag_pipeline.tools.searcher import search_hybrid as do_search

    results = do_search(query, source_id, top_k, vector_weight, keyword_weight, embedding_model)
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
def ingest_file(
    source_id: str,
    file_path: str,
    storage_base_path: str,
    document_id: str = "",
    chunking_strategy: str = "semantic",
    chunking_config: str = "{}",
    embedding_model: str = "",
) -> str:
    """File-based ingestion: read file → parse → chunk → embed → store.

    Sidecar handles the entire pipeline including file parsing.
    Supports: txt, md, csv, html, pdf (PyMuPDF), docx (python-docx), pptx (python-pptx).

    Args:
        source_id: Knowledge source ID
        file_path: Relative file path within storage (e.g. tenant/category/file.pdf)
        storage_base_path: Absolute base path of storage directory
        document_id: Optional document identifier (defaults to file_path)
        chunking_strategy: "semantic", "recursive", or "fixed"
        chunking_config: JSON string with chunking parameters
        embedding_model: Embedding model name (default: KoSimCSE)
    """
    from rag_pipeline.tools.ingestor import ingest_file as do_ingest_file

    cfg = json.loads(chunking_config) if isinstance(chunking_config, str) else chunking_config
    result = do_ingest_file(
        source_id, file_path, storage_base_path,
        document_id, chunking_strategy, cfg, embedding_model,
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
    """Scrape web page content with robots.txt compliance (PY-017, CR-035).

    Modes:
    - basic: urllib + unstructured HTML text extraction
    - js_render: Playwright for JavaScript-rendered pages (optional dependency)
    - firecrawl: Firecrawl API for JS rendering + structured markdown (CR-035 PRD-238)
    - sitemap: Parse sitemap.xml and crawl pages up to max_pages

    Respects robots.txt and applies 1-second rate limiting between requests.
    Firecrawl mode auto-fallbacks to js_render if API key missing or call fails.

    Args:
        url: URL to scrape
        mode: Scraping mode - "basic", "js_render", "firecrawl", or "sitemap"
        max_pages: Maximum pages to crawl in sitemap mode
        timeout_ms: Request timeout in milliseconds
    """
    from rag_pipeline.tools.scraper import scrape_url as do_scrape

    result = do_scrape(url, mode, max_pages, timeout_ms)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def evaluate_rag(
    source_id: str,
    test_set: str = "[]",
    config: str = "{}",
    mode: str = "fast",
) -> str:
    """Evaluate RAG quality using RAGAS metrics (PY-026, CR-016).

    Metrics: faithfulness, context_relevancy, answer_relevancy,
    context_precision, context_recall. ground_truth is optional (BIZ-030).

    Modes:
    - fast (default): embedding similarity based evaluation
    - accurate: LLM Judge based evaluation using Claude (CR-016)

    Args:
        source_id: Knowledge source ID to evaluate
        test_set: JSON array of [{question, ground_truth?, expected_contexts?}]
        config: JSON string with {top_k, metrics}
        mode: Evaluation mode - "fast" (embedding similarity) or "accurate" (LLM Judge)
    """
    from rag_pipeline.tools.evaluator import evaluate_rag as do_evaluate

    ts = json.loads(test_set) if isinstance(test_set, str) else test_set
    cfg = json.loads(config) if isinstance(config, str) else config
    result = do_evaluate(source_id, ts, cfg, mode=mode)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def advanced_parse(
    file_content: str,
    file_type: str = "",
    config: str = "{}",
) -> str:
    """Advanced document parsing with structure preservation (PY-027).

    Extracts sections (headings), tables, and metadata.
    Supports: PDF, HTML, Markdown, TXT.

    Args:
        file_content: Base64-encoded file bytes or plain text
        file_type: File type hint ("pdf", "html", "md", "txt")
        config: JSON string with {extract_tables, preserve_structure}
    """
    from rag_pipeline.tools.advanced_parser import advanced_parse as do_adv_parse

    cfg = json.loads(config) if isinstance(config, str) else config
    result = do_adv_parse(file_content, file_type, cfg)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def cache_lookup(
    query: str,
    source_id: str,
    threshold: float = 0.95,
) -> str:
    """Semantic cache lookup — find cached response for similar query (PY-028).

    Uses pgvector cosine similarity. Hit threshold: 0.95 (BIZ-027).

    Args:
        query: Search query text
        source_id: Knowledge source ID
        threshold: Cosine similarity threshold for cache hit (default 0.95)
    """
    from rag_pipeline.tools.semantic_cache import cache_lookup as do_lookup

    result = do_lookup(query, source_id, threshold)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def cache_store(
    query: str,
    source_id: str,
    response_text: str,
    metadata: str = "{}",
    ttl_hours: int = 24,
) -> str:
    """Store query-response pair in semantic cache (PY-028).

    Args:
        query: Original query text
        source_id: Knowledge source ID
        response_text: Response text to cache
        metadata: JSON string with additional metadata
        ttl_hours: Cache TTL in hours (default 24, 0 = no expiry)
    """
    from rag_pipeline.tools.semantic_cache import cache_store as do_store

    meta = json.loads(metadata) if isinstance(metadata, str) else metadata
    result = do_store(query, source_id, response_text, meta, ttl_hours)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def contextual_chunk(
    content: str,
    document_context: str = "",
    chunking_config: str = "{}",
) -> str:
    """Contextual Retrieval: chunk + LLM-generated context prefix (PY-023).

    Anthropic Contextual Retrieval 기법. 각 청크에 문서 전체 맥락을 설명하는
    접두사를 부여하여 임베딩 시 검색 정확도 49% 향상.

    Args:
        content: Raw document text to chunk
        document_context: Optional full document summary for context generation
        chunking_config: JSON string with {strategy, max_chunk_size, overlap, ...}
    """
    from rag_pipeline.tools.contextual_chunker import contextual_chunk as do_contextual

    cfg = json.loads(chunking_config) if isinstance(chunking_config, str) else chunking_config
    result = do_contextual(content, document_context, cfg)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def parent_child_search(
    query: str,
    source_id: str,
    top_k: int = 5,
) -> str:
    """Parent-Child hierarchical search (PY-024).

    Small child chunks for precision matching → return larger parent chunks
    for richer context. Requires embeddings with parent_id set.

    Args:
        query: Search query text
        source_id: Knowledge source ID to search in
        top_k: Number of parent results to return
    """
    from rag_pipeline.tools.parent_child_search import parent_child_search as do_pc_search

    result = do_pc_search(query, source_id, top_k)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def generate_document(
    code: str,
    output_format: str = "pptx",
    output_filename: str = "",
) -> str:
    """Generate document file by executing LLM-generated Python code (CR-018).

    LLM이 생성한 python-pptx/python-docx/reportlab/openpyxl 코드를 실행하여
    PPTX, DOCX, PDF, XLSX, CSV, HTML 파일을 생성합니다.

    코드 내에서 OUTPUT_PATH 변수를 사용하여 출력 경로를 지정하세요.

    Args:
        code: Python code using document libraries (python-pptx, python-docx, reportlab, openpyxl)
        output_format: Output format - "pptx", "docx", "pdf", "xlsx", "csv", "html"
        output_filename: Output filename (auto-generated if empty)
    """
    from rag_pipeline.tools.document_generator import generate_document as do_generate

    result = do_generate(code, output_format, output_filename)
    # file_base64가 너무 크면 MCP 응답에 포함 — 호출자가 처리
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def list_document_formats() -> str:
    """List supported document generation formats and their availability (CR-018).

    Returns available document formats (PPTX, DOCX, PDF, XLSX, CSV, HTML)
    and whether their required libraries are installed.
    """
    from rag_pipeline.tools.document_generator import list_supported_formats

    result = list_supported_formats()
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def save_document_template(
    name: str,
    format: str,
    template_type: str = "code",
    code_template: str = "",
    variables: str = "[]",
    description: str = "",
    tags: str = "[]",
    created_by: str = "",
) -> str:
    """Save a document template for reuse (CR-018).

    Save Python code as a reusable template with variable placeholders ({{var_name}}).
    Variables are auto-extracted from the code if not specified.

    Args:
        name: Template name
        format: Output format (pptx, docx, pdf, xlsx, csv, html, png, jpg, svg)
        template_type: 'code' or 'file'
        code_template: Python code template with {{variable}} placeholders
        variables: JSON array of variable definitions [{name, type, default_value, required, description}]
        description: Template description
        tags: JSON array of tags
        created_by: Creator identifier
    """
    from rag_pipeline.tools.template_manager import save_template

    result = save_template(
        name=name, format=format, template_type=template_type,
        code_template=code_template, variables=variables,
        description=description, tags=tags, created_by=created_by,
    )
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def list_document_templates(
    format: str = "",
    template_type: str = "",
) -> str:
    """List saved document templates (CR-018).

    Args:
        format: Filter by format (empty for all)
        template_type: Filter by type 'code' or 'file' (empty for all)
    """
    from rag_pipeline.tools.template_manager import list_templates

    result = list_templates(format=format, template_type=template_type)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def get_document_template(template_id: str) -> str:
    """Get document template details including code (CR-018).

    Args:
        template_id: Template UUID
    """
    from rag_pipeline.tools.template_manager import get_template

    result = get_template(template_id)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def delete_document_template(template_id: str) -> str:
    """Delete a document template (CR-018).

    Args:
        template_id: Template UUID
    """
    from rag_pipeline.tools.template_manager import delete_template

    result = delete_template(template_id)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def render_document_template(
    template_id: str,
    variables: str = "{}",
    output_format: str = "",
) -> str:
    """Generate a document from a saved template (CR-018).

    Supports both template types:
    - code: Replaces {{variable}} in Python code, then executes to generate document
    - file: Replaces {{variable}} in uploaded PPTX/DOCX file, preserving original design

    Args:
        template_id: Template UUID
        variables: JSON object with variable values {"title": "Report", "items": [...]}
        output_format: Output file format (pdf, docx, pptx). Empty = same as template format.
    """
    # 먼저 템플릿 타입을 확인하여 적절한 렌더러로 분기
    from rag_pipeline.tools.template_manager import get_template

    tpl_result = get_template(template_id)
    if not tpl_result.get("success"):
        return json.dumps(tpl_result, ensure_ascii=False)

    tpl_type = tpl_result["template"]["template_type"]

    if tpl_type == "file":
        from rag_pipeline.tools.file_template import render_file_template
        result = render_file_template(template_id=template_id, variables=variables, output_format=output_format)
    else:
        from rag_pipeline.tools.template_manager import render_template
        result = render_template(template_id=template_id, variables=variables)

    return json.dumps(result, ensure_ascii=False, default=str)


@mcp.tool()
def upload_file_template(
    name: str,
    format: str,
    file_base64: str,
    original_filename: str = "",
    description: str = "",
    tags: str = "[]",
) -> str:
    """Upload a PPTX/DOCX file as a template (CR-018).

    Saves the file, extracts {{variable}} placeholders from all text,
    and registers as a file-type template.

    Args:
        name: Template name
        format: File format (pptx or docx)
        file_base64: Base64-encoded file content
        original_filename: Original file name
        description: Template description
        tags: JSON array of tags
    """
    from rag_pipeline.tools.file_template import upload_file_template as do_upload

    result = do_upload(
        name=name, format=format, file_base64=file_base64,
        original_filename=original_filename, description=description, tags=tags,
    )
    return json.dumps(result, ensure_ascii=False)


# ── CR-013: Document Intelligence (스키마 기반 문서 생성) ─────


@mcp.tool()
def generate_document_from_schema(
    schema: str,
    output_format: str = "pptx",
    theme_name: str = "default",
    custom_theme: str = "{}",
    output_filename: str = "",
) -> str:
    """Generate document from structured JSON schema with theme support (CR-013).

    LLM이 구조화된 JSON 스키마를 생성하면, 렌더러가 테마를 적용하여
    PPTX/DOCX/PDF로 변환합니다. 코드 실행 없이 안정적인 문서 생성.

    Presentation schema example:
    {
        "type": "presentation",
        "slides": [
            {"layout": "title", "title": "보고서", "subtitle": "2026 Q1"},
            {"layout": "title_content", "title": "목차", "content": {"type": "bullets", "items": ["항목1", "항목2"]}},
            {"layout": "two_column", "title": "비교", "left": {"type": "bullets", "items": [...]}, "right": {"type": "bullets", "items": [...]}},
            {"layout": "table", "title": "데이터", "table": {"headers": ["항목", "값"], "rows": [["A", "100"]]}},
            {"layout": "chart", "title": "추이", "chart": {"type": "bar", "categories": ["1월","2월"], "series": [{"name": "매출", "values": [100, 200]}]}}
        ]
    }

    Document schema example:
    {
        "type": "document",
        "title": "보고서",
        "sections": [
            {"type": "heading", "level": 1, "text": "개요"},
            {"type": "paragraph", "text": "본문..."},
            {"type": "bullets", "items": ["항목1", "항목2"]},
            {"type": "table", "headers": ["항목", "값"], "rows": [["A", "100"]]}
        ]
    }

    Args:
        schema: Document structure JSON (presentation or document type)
        output_format: Output format - "pptx", "docx", "pdf"
        theme_name: Theme preset - "default", "corporate_blue", "modern_dark", "minimal", "warm"
        custom_theme: Custom theme overrides JSON (merged on top of preset)
        output_filename: Output filename (auto-generated if empty)
    """
    from rag_pipeline.tools.document_intelligence import generate_from_schema

    result = generate_from_schema(
        schema=schema, output_format=output_format,
        theme_name=theme_name, custom_theme=custom_theme,
        output_filename=output_filename,
    )
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def validate_document_schema(schema: str) -> str:
    """Validate a document schema before generation (CR-013).

    Checks structure, required fields, layout types, and content formats.
    Returns validation result with errors and warnings.

    Args:
        schema: Document structure JSON to validate
    """
    from rag_pipeline.tools.document_intelligence import validate_schema

    try:
        parsed = json.loads(schema) if isinstance(schema, str) else schema
    except json.JSONDecodeError as e:
        return json.dumps({"valid": False, "errors": [f"Invalid JSON: {e}"], "warnings": []})

    result = validate_schema(parsed)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def list_document_themes() -> str:
    """List available document themes/presets (CR-013).

    Returns theme names with preview colors and fonts.
    Themes: default, corporate_blue, modern_dark, minimal, warm.
    """
    from rag_pipeline.tools.document_intelligence import list_themes

    result = list_themes()
    return json.dumps(result, ensure_ascii=False)


# ── CR-019: 플랫폼 공통 Tool ─────────────────────────────────


@mcp.tool()
def read_pptx(
    file_base64: str,
    output_mode: str = "json",
) -> str:
    """Read a PPTX file and convert to structured JSON/HTML (CR-019).

    Extracts all slides with text, tables, images, and layout info in a single call.

    Args:
        file_base64: Base64-encoded PPTX file content
        output_mode: Output format - "json" (structured data) or "html" (rendered HTML)
    """
    from rag_pipeline.tools.document_reader import read_pptx as do_read
    result = do_read(file_base64=file_base64, output_mode=output_mode)
    return json.dumps(result, ensure_ascii=False, default=str)


@mcp.tool()
def read_docx(
    file_base64: str,
    output_mode: str = "json",
) -> str:
    """Read a DOCX file and convert to structured JSON/HTML (CR-019).

    Extracts paragraphs, tables, images, headers/footers with style information.

    Args:
        file_base64: Base64-encoded DOCX file content
        output_mode: Output format - "json" or "html"
    """
    from rag_pipeline.tools.document_reader import read_docx as do_read
    result = do_read(file_base64=file_base64, output_mode=output_mode)
    return json.dumps(result, ensure_ascii=False, default=str)


@mcp.tool()
def read_excel(
    file_base64: str,
    sheet_name: str = "",
    max_rows: int = 0,
) -> str:
    """Read an XLSX/CSV file and convert to JSON per sheet (CR-019).

    Args:
        file_base64: Base64-encoded XLSX or CSV file content
        sheet_name: Specific sheet to read (empty = all sheets)
        max_rows: Maximum rows per sheet (0 = all rows)
    """
    from rag_pipeline.tools.document_reader import read_excel as do_read
    result = do_read(file_base64=file_base64, sheet_name=sheet_name, max_rows=max_rows)
    return json.dumps(result, ensure_ascii=False, default=str)


@mcp.tool()
def read_pdf(
    file_base64: str,
    extract_images: bool = False,
    ocr_enabled: bool = False,
) -> str:
    """Read a PDF file and extract text/structure per page (CR-019).

    Args:
        file_base64: Base64-encoded PDF file content
        extract_images: Whether to extract embedded images as base64
        ocr_enabled: Enable OCR for scanned pages (requires pytesseract)
    """
    from rag_pipeline.tools.document_reader import read_pdf as do_read
    result = do_read(file_base64=file_base64, extract_images=extract_images, ocr_enabled=ocr_enabled)
    return json.dumps(result, ensure_ascii=False, default=str)


@mcp.tool()
def convert_format(
    file_base64: str,
    input_format: str,
    output_format: str,
) -> str:
    """Convert document between formats using LibreOffice (CR-019).

    Supports: DOCX, PDF, PPTX, HTML, XLSX, CSV, and more.

    Args:
        file_base64: Base64-encoded source file
        input_format: Source format (e.g., docx, pptx, pdf, html)
        output_format: Target format (e.g., pdf, docx, html)
    """
    from rag_pipeline.tools.format_converter import convert_format as do_convert
    result = do_convert(file_base64=file_base64, input_format=input_format, output_format=output_format)
    return json.dumps(result, ensure_ascii=False, default=str)


@mcp.tool()
def run_python(
    code: str,
    timeout: int = 30,
) -> str:
    """Execute Python code in a sandboxed environment (CR-019).

    Use OUTPUT_DIR variable to save generated files. They will be returned as base64.

    Args:
        code: Python code to execute
        timeout: Execution timeout in seconds (max 300)
    """
    from rag_pipeline.tools.code_executor import run_python as do_run
    result = do_run(code=code, timeout=timeout)
    return json.dumps(result, ensure_ascii=False, default=str)


@mcp.tool()
def render_chart(
    chart_type: str,
    data: str,
    title: str = "",
    options: str = "{}",
) -> str:
    """Render data as a chart image (CR-019).

    Args:
        chart_type: Chart type - bar, line, pie, scatter, hbar
        data: JSON data. Format: {"labels": [...], "values": [...]} or {"labels": [...], "datasets": [{"name": "A", "values": [...]}]}
        title: Chart title
        options: JSON options: {width, height, xlabel, ylabel, colors, legend}
    """
    from rag_pipeline.tools.chart_renderer import render_chart as do_render
    result = do_render(chart_type=chart_type, data=data, title=title, options=options)
    return json.dumps(result, ensure_ascii=False, default=str)


@mcp.tool()
def web_search(
    query: str,
    max_results: int = 5,
) -> str:
    """Search the web and return results (CR-019).

    Args:
        query: Search query
        max_results: Maximum number of results (default 5)
    """
    from rag_pipeline.tools.web_tools import web_search as do_search
    result = do_search(query=query, max_results=max_results)
    return json.dumps(result, ensure_ascii=False, default=str)


@mcp.tool()
def web_fetch(
    url: str,
    extract_mode: str = "text",
) -> str:
    """Fetch and extract clean content from a URL (CR-019).

    Args:
        url: Target URL to fetch
        extract_mode: Extraction mode - "text" (clean text), "html" (raw), "markdown"
    """
    from rag_pipeline.tools.web_tools import web_fetch as do_fetch
    result = do_fetch(url=url, extract_mode=extract_mode)
    return json.dumps(result, ensure_ascii=False, default=str)


def main():
    """Start the MCP server with SSE transport."""
    # CR-036: 프롬프트 템플릿 벌크 로드 (실패 시 로컬 폴백 사용)
    from rag_pipeline import prompt_client
    prompt_client.load_all()

    logger.info(
        "Starting Aimbase RAG Pipeline MCP Server on %s:%d",
        settings.MCP_HOST,
        settings.MCP_PORT,
    )
    mcp.run(transport="sse", host=settings.MCP_HOST, port=settings.MCP_PORT)


if __name__ == "__main__":
    main()
