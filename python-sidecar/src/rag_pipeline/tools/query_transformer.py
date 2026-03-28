"""PY-005 + PY-025: Query Transformation MCP Tool.

Transforms user queries for improved retrieval quality.
Strategies: HyDE (Hypothetical Document Embedding), Multi-Query, Step-Back.

v2.0 (CR-011, PY-025): LLM 실제 호출로 업그레이드.
llm_config에 api_key/model/base_url이 있으면 OpenAI-compatible API를 호출하고,
없으면 기존 휴리스틱 fallback.
"""

import logging
import os
import re
from typing import Any

from rag_pipeline.tools.embedder import embed_single

logger = logging.getLogger(__name__)

# LLM 호출용 환경변수 기본값
DEFAULT_LLM_MODEL = os.getenv("QUERY_TRANSFORM_LLM_MODEL", "gpt-4o-mini")
DEFAULT_LLM_BASE_URL = os.getenv("QUERY_TRANSFORM_LLM_BASE_URL", "")
DEFAULT_LLM_API_KEY = os.getenv("QUERY_TRANSFORM_LLM_API_KEY", "")


def transform_query(
    query: str,
    strategy: str = "multi_query",
    llm_config: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Transform a user query into optimized retrieval queries.

    Args:
        query: Original user query
        strategy: "hyde", "multi_query", or "step_back"
        llm_config: Optional LLM config {model, api_key, base_url}

    Returns:
        {original_query, transformed_queries: [], strategy_used, metadata: {}}
    """
    strategy = strategy.lower().strip()
    llm = _resolve_llm_caller(llm_config)

    if strategy == "hyde":
        return _hyde_transform(query, llm)
    elif strategy == "multi_query":
        return _multi_query_transform(query, llm)
    elif strategy == "step_back":
        return _step_back_transform(query, llm)
    else:
        raise ValueError(f"Unknown strategy: {strategy}. Use 'hyde', 'multi_query', or 'step_back'.")


def _hyde_transform(query: str, llm: Any = None) -> dict[str, Any]:
    """HyDE: Hypothetical Document Embedding."""
    if llm:
        prompt = (
            f"다음 질문에 대한 답변이 포함된 가상의 문서 단락(150~300자)을 작성해주세요. "
            f"실제 정보가 아니어도 됩니다. 검색용 가상 문서입니다.\n\n"
            f"질문: {query}\n\n가상 문서:"
        )
        hypothesis = _call_llm(llm, prompt)
    else:
        hypothesis = _generate_hypothesis_heuristic(query)

    hypothesis_embedding = embed_single(hypothesis)

    return {
        "original_query": query,
        "transformed_queries": [query, hypothesis],
        "strategy_used": "hyde",
        "metadata": {
            "hypothesis": hypothesis,
            "hypothesis_embedding": hypothesis_embedding,
            "llm_used": llm is not None,
        },
    }


def _multi_query_transform(query: str, llm: Any = None) -> dict[str, Any]:
    """Multi-Query: Generate multiple diverse query variations."""
    if llm:
        prompt = (
            f"다음 질문을 검색 품질을 높이기 위해 3~5개의 다른 표현으로 변환해주세요. "
            f"각 변형은 의미는 같지만 다른 어휘와 관점을 사용해야 합니다.\n\n"
            f"원본 질문: {query}\n\n"
            f"변형 질문들 (한 줄에 하나씩, 번호 없이):"
        )
        raw = _call_llm(llm, prompt)
        variations = [line.strip().lstrip("- ").lstrip("•").strip()
                       for line in raw.strip().split("\n")
                       if line.strip() and len(line.strip()) > 5]
        variations = variations[:5]  # 최대 5개
    else:
        variations = _generate_query_variations_heuristic(query)

    return {
        "original_query": query,
        "transformed_queries": [query] + variations,
        "strategy_used": "multi_query",
        "metadata": {
            "variation_count": len(variations),
            "llm_used": llm is not None,
        },
    }


def _step_back_transform(query: str, llm: Any = None) -> dict[str, Any]:
    """Step-Back: Generate a more general/abstract version of the query."""
    if llm:
        prompt = (
            f"다음 질문을 더 일반적이고 추상적인 상위 개념 질문으로 변환해주세요. "
            f"구체적인 세부사항을 제거하고 핵심 개념에 초점을 맞추세요.\n\n"
            f"구체적 질문: {query}\n\n"
            f"추상화된 질문:"
        )
        step_back_query = _call_llm(llm, prompt).strip()
    else:
        step_back_query = _generate_step_back_heuristic(query)

    return {
        "original_query": query,
        "transformed_queries": [query, step_back_query],
        "strategy_used": "step_back",
        "metadata": {
            "step_back_query": step_back_query,
            "llm_used": llm is not None,
        },
    }


# ── LLM 호출 ──────────────────────────────────────────────────────────


def _resolve_llm_caller(llm_config: dict[str, Any] | None) -> dict | None:
    """LLM 호출에 필요한 설정을 결정. API 키가 있으면 dict 반환, 없으면 None."""
    cfg = llm_config or {}
    api_key = cfg.get("api_key") or DEFAULT_LLM_API_KEY
    if not api_key:
        return None

    return {
        "api_key": api_key,
        "model": cfg.get("model") or DEFAULT_LLM_MODEL,
        "base_url": cfg.get("base_url") or DEFAULT_LLM_BASE_URL or None,
    }


def _call_llm(llm_cfg: dict, prompt: str) -> str:
    """OpenAI-compatible API를 호출하여 텍스트 생성."""
    try:
        import httpx

        base_url = llm_cfg.get("base_url") or "https://api.openai.com/v1"
        headers = {
            "Authorization": f"Bearer {llm_cfg['api_key']}",
            "Content-Type": "application/json",
        }
        payload = {
            "model": llm_cfg["model"],
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 500,
            "temperature": 0.7,
        }

        with httpx.Client(timeout=30.0) as client:
            resp = client.post(f"{base_url.rstrip('/')}/chat/completions",
                               headers=headers, json=payload)
            resp.raise_for_status()
            data = resp.json()
            return data["choices"][0]["message"]["content"].strip()

    except ImportError:
        logger.warning("httpx not installed, falling back to heuristic")
        return ""
    except Exception as e:
        logger.warning("LLM call failed: %s, falling back to heuristic", e)
        return ""


# ── Heuristic fallbacks (LLM-free) ──────────────────────────────────


def _generate_hypothesis_heuristic(query: str) -> str:
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


def _generate_query_variations_heuristic(query: str) -> list[str]:
    variations = []
    variations.append(f"{query} 관련 정보")

    keywords = [w for w in query.split() if len(w) > 1]
    if len(keywords) >= 2:
        variations.append(" ".join(keywords[:5]) + " 설명")
    else:
        variations.append(f"{query} 정의 개념")

    if query.endswith("?") or query.endswith("요") or query.endswith("까"):
        variations.append(f"{query.rstrip('?요까')}에 대해 알려주세요")
    else:
        variations.append(f"{query}란 무엇인가요?")

    return variations


def _generate_step_back_heuristic(query: str) -> str:
    abstracted = re.sub(r"\d{4}[-/]\d{1,2}[-/]\d{1,2}", "", query)
    abstracted = re.sub(r"\d+", "", abstracted)
    abstracted = abstracted.strip()
    if abstracted and abstracted != query:
        return f"{abstracted} 전반적인 개요"
    else:
        return f"{query} 기본 개념과 배경"
