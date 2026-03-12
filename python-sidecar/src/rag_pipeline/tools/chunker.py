"""PY-001: Semantic Chunking MCP Tool.

Splits documents into semantic units using LlamaIndex SemanticSplitter.
Supports semantic, recursive, and fixed strategies.
"""

from llama_index.core.node_parser import (
    SemanticSplitterNodeParser,
    SentenceSplitter,
)
from llama_index.core.schema import Document
from llama_index.embeddings.huggingface import HuggingFaceEmbedding

from rag_pipeline.config import settings

# Lazy-loaded embedding model for semantic chunking
_embed_model = None


def _get_embed_model(model_name: str = "") -> HuggingFaceEmbedding:
    global _embed_model
    name = model_name or settings.DEFAULT_EMBED_MODEL
    if _embed_model is None or _embed_model.model_name != name:
        _embed_model = HuggingFaceEmbedding(model_name=name)
    return _embed_model


def chunk_document(
    content: str,
    strategy: str = "semantic",
    config: dict | None = None,
) -> list[dict]:
    """Split document content into chunks.

    Args:
        content: Raw document text
        strategy: "semantic" | "recursive" | "fixed"
        config: {max_chunk_size, similarity_threshold, overlap}

    Returns:
        List of {content, metadata, token_count}
    """
    if not content or not content.strip():
        return []

    cfg = config or {}
    max_chunk_size = cfg.get("max_chunk_size", settings.DEFAULT_CHUNK_SIZE)
    overlap = cfg.get("overlap", settings.DEFAULT_CHUNK_OVERLAP)
    similarity_threshold = cfg.get(
        "similarity_threshold", settings.DEFAULT_SIMILARITY_THRESHOLD
    )

    doc = Document(text=content)

    if strategy == "semantic":
        embed_model = _get_embed_model()
        parser = SemanticSplitterNodeParser(
            embed_model=embed_model,
            buffer_size=1,
            breakpoint_percentile_threshold=int(similarity_threshold * 100),
        )
    elif strategy == "recursive":
        parser = SentenceSplitter(
            chunk_size=max_chunk_size,
            chunk_overlap=overlap,
        )
    else:  # fixed
        parser = SentenceSplitter(
            chunk_size=max_chunk_size,
            chunk_overlap=overlap,
            paragraph_separator="\n\n",
        )

    nodes = parser.get_nodes_from_documents([doc])

    chunks = []
    for i, node in enumerate(nodes):
        text = node.get_content()
        chunks.append({
            "content": text,
            "metadata": {
                "chunk_index": i,
                "strategy": strategy,
            },
            "token_count": len(text.split()),  # approximate
        })

    return chunks
