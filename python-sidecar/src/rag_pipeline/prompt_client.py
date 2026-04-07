"""CR-036 PRD-254: Prompt Template Client.

Loads prompt templates from Aimbase BE API at startup.
Falls back to local defaults if API is unavailable.
"""

import logging
from pathlib import Path
from typing import Optional

import httpx

from rag_pipeline.config import settings

logger = logging.getLogger(__name__)

# Local fallback directory
_FALLBACK_DIR = Path(__file__).parent / "prompts"

# In-memory cache (loaded once at startup)
_cache: dict[str, str] = {}
_loaded: bool = False


def load_all() -> None:
    """Load all prompt templates from BE API (called once at startup)."""
    global _cache, _loaded
    try:
        url = f"{settings.AIMBASE_API_URL}/api/v1/prompt-templates/bulk"
        resp = httpx.get(url, timeout=10.0)
        if resp.status_code == 200:
            data = resp.json()
            # ApiResponse<Map<String, String>> 구조: {"success": true, "data": {...}}
            if isinstance(data, dict) and "data" in data:
                _cache = data["data"]
            elif isinstance(data, dict):
                _cache = data
            _loaded = True
            logger.info("Loaded %d prompt templates from API", len(_cache))
        else:
            logger.warning("Failed to load prompts from API: status=%d", resp.status_code)
    except Exception as e:
        logger.warning("Failed to load prompts from API: %s (using local fallbacks)", e)


def get(key: str, fallback: Optional[str] = None) -> str:
    """Get a prompt template by key.

    Lookup order: memory cache -> local file -> fallback parameter.
    """
    # 1. Memory cache
    if key in _cache:
        return _cache[key]

    # 2. Local file fallback
    file_path = _FALLBACK_DIR / (key.replace(".", "/") + ".txt")
    if file_path.exists():
        content = file_path.read_text(encoding="utf-8").strip()
        _cache[key] = content
        return content

    # 3. Fallback parameter
    if fallback is not None:
        return fallback

    logger.warning("Prompt template not found: %s", key)
    return ""


def render(key: str, variables: dict[str, str], fallback: Optional[str] = None) -> str:
    """Get and render a prompt template with variable substitution."""
    template = get(key, fallback)
    for var_name, var_value in variables.items():
        template = template.replace("{{" + var_name + "}}", str(var_value))
    return template


def is_loaded() -> bool:
    """Check if templates were loaded from API."""
    return _loaded
