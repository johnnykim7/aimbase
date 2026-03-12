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


settings = Settings()
