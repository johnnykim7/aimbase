"""Evaluation MCP Server configuration."""

import os


class Settings:
    # Server
    MCP_HOST: str = os.getenv("EVALUATION_MCP_HOST", "0.0.0.0")
    MCP_PORT: int = int(os.getenv("EVALUATION_MCP_PORT", "8002"))

    # LLM for evaluation (RAGAS/DeepEval use LLM internally)
    OPENAI_API_KEY: str = os.getenv("OPENAI_API_KEY", "")
    EVAL_LLM_MODEL: str = os.getenv("EVAL_LLM_MODEL", "gpt-4o-mini")

    # RAGAS defaults
    RAGAS_BATCH_SIZE: int = int(os.getenv("RAGAS_BATCH_SIZE", "5"))

    # DeepEval defaults
    DEEPEVAL_DEFAULT_THRESHOLD: float = float(os.getenv("DEEPEVAL_THRESHOLD", "0.5"))

    # Database (pgvector) — for drift detection
    DB_HOST: str = os.getenv("DB_HOST", "localhost")
    DB_PORT: int = int(os.getenv("DB_PORT", "5433"))
    DB_NAME: str = os.getenv("DB_NAME", "aimbase_tenant_dev")
    DB_USER: str = os.getenv("DB_USER", "platform")
    DB_PASSWORD: str = os.getenv("DB_PASSWORD", "platform")

    @property
    def db_url(self) -> str:
        return f"postgresql://{self.DB_USER}:{self.DB_PASSWORD}@{self.DB_HOST}:{self.DB_PORT}/{self.DB_NAME}"


settings = Settings()
