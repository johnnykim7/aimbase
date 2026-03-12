"""Pydantic schemas for MCP tool inputs/outputs."""

from pydantic import BaseModel, Field


class ChunkResult(BaseModel):
    content: str
    metadata: dict = Field(default_factory=dict)
    token_count: int = 0


class ChunkDocumentInput(BaseModel):
    content: str
    strategy: str = "semantic"  # "semantic" | "recursive" | "fixed"
    config: dict = Field(default_factory=lambda: {
        "max_chunk_size": 512,
        "similarity_threshold": 0.7,
    })


class EmbedTextsInput(BaseModel):
    texts: list[str]
    model: str = ""  # empty → use default


class SearchHybridInput(BaseModel):
    query: str
    source_id: str
    top_k: int = 5
    vector_weight: float = 0.7
    keyword_weight: float = 0.3


class RerankInput(BaseModel):
    query: str
    documents: list[dict]  # [{content, metadata}]
    top_k: int = 5
    model: str = ""


class IngestDocumentInput(BaseModel):
    source_id: str
    content: str
    document_id: str = ""
    chunking_strategy: str = "semantic"
    chunking_config: dict = Field(default_factory=dict)
    embedding_model: str = ""
