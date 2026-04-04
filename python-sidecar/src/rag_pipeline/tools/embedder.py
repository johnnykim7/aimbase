"""PY-002: Embedding MCP Tool.

멀티 프로바이더 임베딩 지원:
- OpenAI API: text-embedding-3-small/large, ada-002
- Cohere API: embed-v4, embed-v3.0 등
- Voyage API: voyage-3, voyage-code-3 등
- 로컬 모델: BGE-M3, KoSimCSE 등 sentence-transformers 호환 모델
"""

import logging
import os
from typing import Any

from sentence_transformers import SentenceTransformer

from rag_pipeline.config import settings

logger = logging.getLogger(__name__)

# Cache loaded local models
_models: dict[str, SentenceTransformer] = {}

# ── 프로바이더별 모델 판별 ──────────────────────────────────────

OPENAI_EMBED_MODELS = {
    "text-embedding-3-small",
    "text-embedding-3-large",
    "text-embedding-ada-002",
}

COHERE_EMBED_MODELS = {
    "embed-v4.0",
    "embed-multilingual-v3.0",
    "embed-english-v3.0",
    "embed-multilingual-light-v3.0",
    "embed-english-light-v3.0",
}

VOYAGE_EMBED_MODELS = {
    "voyage-3",
    "voyage-3-lite",
    "voyage-code-3",
    "voyage-finance-2",
    "voyage-law-2",
    "voyage-multilingual-2",
}


def _is_openai_model(model_name: str) -> bool:
    return model_name in OPENAI_EMBED_MODELS or model_name.startswith("text-embedding-")


def _is_cohere_model(model_name: str) -> bool:
    return model_name in COHERE_EMBED_MODELS or model_name.startswith("embed-")


def _is_voyage_model(model_name: str) -> bool:
    return model_name in VOYAGE_EMBED_MODELS or model_name.startswith("voyage-")


def _get_local_model(model_name: str = "") -> SentenceTransformer:
    name = model_name or settings.DEFAULT_EMBED_MODEL
    if name not in _models:
        logger.info("Loading local embedding model: %s", name)
        _models[name] = SentenceTransformer(name)
    return _models[name]


def _embed_openai(texts: list[str], model_name: str) -> dict[str, Any]:
    """OpenAI Embedding API 호출."""
    import httpx

    api_key = os.getenv("OPENAI_API_KEY", "")
    if not api_key:
        try:
            from rag_pipeline.db import get_openai_api_key
            api_key = get_openai_api_key()
        except Exception:
            pass

    if not api_key:
        raise ValueError("OPENAI_API_KEY not set. Set env var or register OpenAI connection in DB.")

    # OpenAI API 호출
    response = httpx.post(
        "https://api.openai.com/v1/embeddings",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        json={
            "model": model_name,
            "input": texts,
        },
        timeout=60.0,
    )
    response.raise_for_status()
    data = response.json()

    embeddings = [item["embedding"] for item in data["data"]]
    dimensions = len(embeddings[0]) if embeddings else 0
    usage = data.get("usage", {})

    logger.info("OpenAI embedding (%s): %d texts, %d dims, %d tokens",
                model_name, len(texts), dimensions, usage.get("total_tokens", 0))

    return {
        "embeddings": embeddings,
        "model": model_name,
        "dimensions": dimensions,
        "provider": "openai",
        "usage": usage,
    }


def _embed_cohere(texts: list[str], model_name: str) -> dict[str, Any]:
    """Cohere Embedding API 호출."""
    import httpx

    api_key = os.getenv("COHERE_API_KEY", "")
    if not api_key:
        raise ValueError("COHERE_API_KEY not set. Set env var or register Cohere connection.")

    response = httpx.post(
        "https://api.cohere.com/v2/embed",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        json={
            "model": model_name,
            "texts": texts,
            "input_type": "search_document",
            "embedding_types": ["float"],
        },
        timeout=60.0,
    )
    response.raise_for_status()
    data = response.json()

    embeddings = data.get("embeddings", {}).get("float", [])
    dimensions = len(embeddings[0]) if embeddings else 0
    usage = data.get("meta", {}).get("billed_units", {})

    logger.info("Cohere embedding (%s): %d texts, %d dims",
                model_name, len(texts), dimensions)

    return {
        "embeddings": embeddings,
        "model": model_name,
        "dimensions": dimensions,
        "provider": "cohere",
        "usage": usage,
    }


def _embed_voyage(texts: list[str], model_name: str) -> dict[str, Any]:
    """Voyage AI Embedding API 호출."""
    import httpx

    api_key = os.getenv("VOYAGE_API_KEY", "")
    if not api_key:
        raise ValueError("VOYAGE_API_KEY not set. Set env var or register Voyage connection.")

    response = httpx.post(
        "https://api.voyageai.com/v1/embeddings",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        json={
            "model": model_name,
            "input": texts,
            "input_type": "document",
        },
        timeout=60.0,
    )
    response.raise_for_status()
    data = response.json()

    embeddings = [item["embedding"] for item in data.get("data", [])]
    dimensions = len(embeddings[0]) if embeddings else 0
    usage = data.get("usage", {})

    logger.info("Voyage embedding (%s): %d texts, %d dims, %d tokens",
                model_name, len(texts), dimensions, usage.get("total_tokens", 0))

    return {
        "embeddings": embeddings,
        "model": model_name,
        "dimensions": dimensions,
        "provider": "voyage",
        "usage": usage,
    }


def embed_texts(
    texts: list[str],
    model: str = "",
) -> dict:
    """Generate embeddings for a list of texts.

    멀티 프로바이더 지원: 모델명에 따라 자동으로 적절한 API를 호출한다.
    - text-embedding-* → OpenAI API
    - embed-* → Cohere API
    - voyage-* → Voyage AI API
    - 그 외 → 로컬 sentence-transformers

    Args:
        texts: List of text strings to embed
        model: Model name. 미지정 시 로컬 기본 모델 (BGE-M3).

    Returns:
        {embeddings: float[][], model: str, dimensions: int, provider: str}
    """
    model_name = model or settings.DEFAULT_EMBED_MODEL

    if _is_openai_model(model_name):
        return _embed_openai(texts, model_name)

    if _is_cohere_model(model_name):
        return _embed_cohere(texts, model_name)

    if _is_voyage_model(model_name):
        return _embed_voyage(texts, model_name)

    # 로컬 모델 (sentence-transformers)
    st_model = _get_local_model(model_name)
    vectors = st_model.encode(texts, normalize_embeddings=True)

    return {
        "embeddings": vectors.tolist(),
        "model": model_name,
        "dimensions": vectors.shape[1],
        "provider": "local",
    }


def embed_single(text: str, model: str = "") -> list[float]:
    """Embed a single text string. Returns raw vector."""
    result = embed_texts([text], model)
    return result["embeddings"][0]
