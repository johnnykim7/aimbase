"""PY-005: Query Transformation MCP Tool.

Transforms user queries for improved retrieval quality.
Strategies: HyDE (Hypothetical Document Embedding), Multi-Query, Step-Back.
"""

import logging
from typing import Any

from rag_pipeline.tools.embedder import embed_single

logger = logging.getLogger(__name__)


def transform_query(
    query: str,
    strategy: str = "multi_query",
    llm_config: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Transform a user query into optimized retrieval queries.

    Args:
        query: Original user query
        strategy: "hyde", "multi_query", or "step_back"
        llm_config: Optional LLM config (for future Spring orchestrator callback)

    Returns:
        {original_query, transformed_queries: [], strategy_used, metadata: {}}
    """
    strategy = strategy.lower().strip()

    if strategy == "hyde":
        return _hyde_transform(query)
    elif strategy == "multi_query":
        return _multi_query_transform(query)
    elif strategy == "step_back":
        return _step_back_transform(query)
    else:
        raise ValueError(f"Unknown strategy: {strategy}. Use 'hyde', 'multi_query', or 'step_back'.")


def _hyde_transform(query: str) -> dict[str, Any]:
    """HyDE: Hypothetical Document Embedding.

    Generates a hypothetical answer to the query, then uses that as additional
    retrieval context. Without an LLM, we create a template-based hypothesis.
    """
    hypothesis = _generate_hypothesis(query)
    hypothesis_embedding = embed_single(hypothesis)

    return {
        "original_query": query,
        "transformed_queries": [query, hypothesis],
        "strategy_used": "hyde",
        "metadata": {
            "hypothesis": hypothesis,
            "hypothesis_embedding": hypothesis_embedding,
        },
    }


def _multi_query_transform(query: str) -> dict[str, Any]:
    """Multi-Query: Generate multiple diverse query variations.

    Creates semantically equivalent but lexically different queries
    to improve recall across different document phrasings.
    """
    variations = _generate_query_variations(query)

    return {
        "original_query": query,
        "transformed_queries": [query] + variations,
        "strategy_used": "multi_query",
        "metadata": {
            "variation_count": len(variations),
        },
    }


def _step_back_transform(query: str) -> dict[str, Any]:
    """Step-Back: Generate a more general/abstract version of the query.

    Useful when specific queries don't match documents well —
    a broader query can capture relevant high-level context.
    """
    step_back_query = _generate_step_back(query)

    return {
        "original_query": query,
        "transformed_queries": [query, step_back_query],
        "strategy_used": "step_back",
        "metadata": {
            "step_back_query": step_back_query,
        },
    }


# ── Heuristic generators (LLM-free) ─────────────────────────────────


def _generate_hypothesis(query: str) -> str:
    """Generate a hypothetical document that would answer the query.

    Uses template-based generation. In production, this should call an LLM
    via Spring orchestrator for higher quality hypotheses.
    """
    templates = {
        "what": "다음은 '{query}'에 대한 설명입니다: 이 주제는 여러 측면에서 중요하며, 핵심 개념과 관련 사항을 포함합니다.",
        "how": "다음은 '{query}'에 대한 방법을 설명합니다: 이 과정은 여러 단계로 구성되어 있으며, 각 단계의 세부 사항을 다룹니다.",
        "why": "다음은 '{query}'에 대한 이유를 설명합니다: 이 현상의 원인은 여러 요인에 의해 발생하며, 그 배경과 맥락을 포함합니다.",
        "default": "다음은 '{query}'에 대한 관련 문서입니다: 이 내용은 해당 주제의 핵심 정보와 세부 사항을 포함하고 있습니다.",
    }

    query_lower = query.lower()
    if any(kw in query_lower for kw in ["무엇", "what", "뭐"]):
        template = templates["what"]
    elif any(kw in query_lower for kw in ["어떻게", "how", "방법"]):
        template = templates["how"]
    elif any(kw in query_lower for kw in ["왜", "why", "이유"]):
        template = templates["why"]
    else:
        template = templates["default"]

    return template.format(query=query)


def _generate_query_variations(query: str) -> list[str]:
    """Generate diverse query variations using heuristic rewriting.

    Produces 3 variations: synonym-based, question-reformulation, keyword-focused.
    In production, use LLM for higher quality variations.
    """
    variations = []

    # Variation 1: Append context hint
    variations.append(f"{query} 관련 정보")

    # Variation 2: Keyword extraction style
    keywords = [w for w in query.split() if len(w) > 1]
    if len(keywords) >= 2:
        variations.append(" ".join(keywords[:5]) + " 설명")
    else:
        variations.append(f"{query} 정의 개념")

    # Variation 3: Question reformulation
    if query.endswith("?") or query.endswith("요") or query.endswith("까"):
        variations.append(f"{query.rstrip('?요까')}에 대해 알려주세요")
    else:
        variations.append(f"{query}란 무엇인가요?")

    return variations


def _generate_step_back(query: str) -> str:
    """Generate a broader, more abstract version of the query.

    Removes specifics to capture general context that might be missed
    by the original narrow query.
    """
    # Remove specific details (numbers, dates, proper nouns patterns)
    import re

    # Remove numbers and dates
    abstracted = re.sub(r"\d{4}[-/]\d{1,2}[-/]\d{1,2}", "", query)
    abstracted = re.sub(r"\d+", "", abstracted)

    # Generalize
    abstracted = abstracted.strip()
    if abstracted and abstracted != query:
        return f"{abstracted} 전반적인 개요"
    else:
        # If no specific details removed, add general framing
        return f"{query} 기본 개념과 배경"
