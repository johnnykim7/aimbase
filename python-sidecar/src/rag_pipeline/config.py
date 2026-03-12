"""RAG Pipeline configuration."""

import os


class Settings:
    # Database
    DB_HOST: str = os.getenv("DB_HOST", "localhost")
    DB_PORT: int = int(os.getenv("DB_PORT", "5433"))
    DB_NAME: str = os.getenv("DB_NAME", "aimbase_tenant_dev")
    DB_USER: str = os.getenv("DB_USER", "platform")
    DB_PASSWORD: str = os.getenv("DB_PASSWORD", "platform")

    # Embedding
    DEFAULT_EMBED_MODEL: str = os.getenv("DEFAULT_EMBED_MODEL", "BM-K/KoSimCSE-roberta-multitask")
    EMBED_DIMENSIONS: int = int(os.getenv("EMBED_DIMENSIONS", "768"))

    # Chunking
    DEFAULT_CHUNK_SIZE: int = int(os.getenv("DEFAULT_CHUNK_SIZE", "512"))
    DEFAULT_CHUNK_OVERLAP: int = int(os.getenv("DEFAULT_CHUNK_OVERLAP", "50"))
    DEFAULT_SIMILARITY_THRESHOLD: float = float(os.getenv("DEFAULT_SIMILARITY_THRESHOLD", "0.7"))

    # Reranking
    DEFAULT_RERANK_MODEL: str = os.getenv("DEFAULT_RERANK_MODEL", "cross-encoder/ms-marco-MiniLM-L-6-v2")

    # Server
    MCP_HOST: str = os.getenv("MCP_HOST", "0.0.0.0")
    MCP_PORT: int = int(os.getenv("MCP_PORT", "8000"))

    @property
    def db_url(self) -> str:
        return f"postgresql://{self.DB_USER}:{self.DB_PASSWORD}@{self.DB_HOST}:{self.DB_PORT}/{self.DB_NAME}"


settings = Settings()
