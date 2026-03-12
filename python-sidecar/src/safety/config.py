"""Safety MCP Server configuration."""

import os


class Settings:
    # Server
    MCP_HOST: str = os.getenv("SAFETY_MCP_HOST", "0.0.0.0")
    MCP_PORT: int = int(os.getenv("SAFETY_MCP_PORT", "8001"))

    # Presidio
    DEFAULT_LANGUAGE: str = os.getenv("SAFETY_DEFAULT_LANGUAGE", "ko")
    DEFAULT_SCORE_THRESHOLD: float = float(os.getenv("SAFETY_SCORE_THRESHOLD", "0.4"))

    # Masking
    DEFAULT_MASK_CHAR: str = os.getenv("SAFETY_MASK_CHAR", "*")


settings = Settings()
