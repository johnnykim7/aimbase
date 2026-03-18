"""Agent MCP Server configuration."""

import os


class Settings:
    # Server
    MCP_HOST: str = os.getenv("AGENT_MCP_HOST", "0.0.0.0")
    MCP_PORT: int = int(os.getenv("AGENT_MCP_PORT", "8003"))

    # Reasoning defaults
    DEFAULT_MAX_STEPS: int = int(os.getenv("AGENT_MAX_STEPS", "10"))
    DEFAULT_STRATEGY: str = os.getenv("AGENT_DEFAULT_STRATEGY", "react")


settings = Settings()
