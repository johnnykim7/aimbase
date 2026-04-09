"""RAG Pipeline configuration."""

import os


class Settings:
    # Database
    DB_HOST: str = os.getenv("DB_HOST", "localhost")
    DB_PORT: int = int(os.getenv("DB_PORT", "5432"))
    DB_NAME: str = os.getenv("DB_NAME", "aimbase_tenant_dev")
    DB_USER: str = os.getenv("DB_USER", "platform")
    DB_PASSWORD: str = os.getenv("DB_PASSWORD", "platform")

    # Embedding (BGE-M3: 다국어 오픈소스 최상위, 한국어 강함)
    # 환경변수로 교체 가능: KoSimCSE(768), OpenAI API 등
    DEFAULT_EMBED_MODEL: str = os.getenv("DEFAULT_EMBED_MODEL", "BAAI/bge-m3")
    EMBED_DIMENSIONS: int = int(os.getenv("EMBED_DIMENSIONS", "1024"))

    # Chunking
    DEFAULT_CHUNK_SIZE: int = int(os.getenv("DEFAULT_CHUNK_SIZE", "512"))
    DEFAULT_CHUNK_OVERLAP: int = int(os.getenv("DEFAULT_CHUNK_OVERLAP", "128"))
    DEFAULT_SIMILARITY_THRESHOLD: float = float(os.getenv("DEFAULT_SIMILARITY_THRESHOLD", "0.7"))

    # Reranking
    DEFAULT_RERANK_MODEL: str = os.getenv("DEFAULT_RERANK_MODEL", "cross-encoder/ms-marco-MiniLM-L-6-v2")

    # Server
    MCP_HOST: str = os.getenv("MCP_HOST", "0.0.0.0")
    MCP_PORT: int = int(os.getenv("MCP_PORT", "8002"))

    # CR-036: Aimbase BE API (프롬프트 템플릿 벌크 로드)
    AIMBASE_API_URL: str = os.getenv("AIMBASE_API_URL", "http://localhost:8181")

    @property
    def db_url(self) -> str:
        return f"postgresql://{self.DB_USER}:{self.DB_PASSWORD}@{self.DB_HOST}:{self.DB_PORT}/{self.DB_NAME}"


settings = Settings()
