"""PY-002: Local Embedding MCP Tool.

Generates embeddings using sentence-transformers (default: KoSimCSE).
"""

from sentence_transformers import SentenceTransformer

from rag_pipeline.config import settings

# Cache loaded models
_models: dict[str, SentenceTransformer] = {}


def _get_model(model_name: str = "") -> SentenceTransformer:
    name = model_name or settings.DEFAULT_EMBED_MODEL
    if name not in _models:
        _models[name] = SentenceTransformer(name)
    return _models[name]


def embed_texts(
    texts: list[str],
    model: str = "",
) -> dict:
    """Generate embeddings for a list of texts.

    Args:
        texts: List of text strings to embed
        model: Model name (default: KoSimCSE)

    Returns:
        {embeddings: float[][], model: str, dimensions: int}
    """
    model_name = model or settings.DEFAULT_EMBED_MODEL
    st_model = _get_model(model_name)
    vectors = st_model.encode(texts, normalize_embeddings=True)

    return {
        "embeddings": vectors.tolist(),
        "model": model_name,
        "dimensions": vectors.shape[1],
    }


def embed_single(text: str, model: str = "") -> list[float]:
    """Embed a single text string. Returns raw vector."""
    result = embed_texts([text], model)
    return result["embeddings"][0]
