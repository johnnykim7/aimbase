"""PY-026: RAG Quality Evaluation (RAGAS) MCP Tool (CR-011, CR-016).

RAGAS 메트릭 5종 평가:
1. Faithfulness — 답변이 컨텍스트에 기반하는지
2. Context Relevancy — 검색된 컨텍스트가 질문에 관련 있는지
3. Answer Relevancy — 답변이 질문에 적절한지
4. Context Precision — 검색 결과 중 관련 문서의 비율
5. Context Recall — ground_truth 대비 검색된 관련 문서 비율

mode:
- "fast" (default) — 임베딩 유사도 기반 평가
- "accurate" — LLM Judge 기반 평가 (CR-016)

ground_truth는 선택사항(BIZ-030).
"""

import logging
import re
import time
from typing import Any

from rag_pipeline.tools.embedder import embed_texts
from rag_pipeline.tools.searcher import search_hybrid

logger = logging.getLogger(__name__)


def evaluate_rag(
    source_id: str,
    test_set: list[dict[str, Any]],
    config: dict[str, Any] | None = None,
    mode: str = "fast",
) -> dict[str, Any]:
    """RAG 품질을 RAGAS 메트릭으로 평가.

    Args:
        source_id: 평가 대상 지식 소스 ID
        test_set: 테스트 셋 [{question, ground_truth?, expected_contexts?}]
        config: {top_k, llm_judge(bool), metrics(list)}
        mode: "fast" (임베딩 유사도) 또는 "accurate" (LLM Judge)

    Returns:
        {metrics: {faithfulness, context_relevancy, ...}, samples: [...], success}
    """
    cfg = config or {}
    top_k = cfg.get("top_k", 5)
    use_llm_judge = mode == "accurate"
    metrics_to_eval = cfg.get("metrics", [
        "faithfulness", "context_relevancy", "answer_relevancy",
        "context_precision", "context_recall",
    ])

    if not test_set:
        return {"metrics": {}, "samples": [], "sample_count": 0, "success": False,
                "error": "test_set is empty"}

    start_time = time.time()
    sample_results = []
    metric_sums: dict[str, float] = {m: 0.0 for m in metrics_to_eval}
    metric_counts: dict[str, int] = {m: 0 for m in metrics_to_eval}

    for item in test_set:
        question = item.get("question", "") or item.get("query", "")
        ground_truth = item.get("ground_truth", "") or item.get("expected_answer", "")
        expected_contexts = item.get("expected_contexts", [])

        if not question:
            continue

        # 검색 수행
        search_results = search_hybrid(question, source_id, top_k)
        retrieved_contents = [r["content"] for r in search_results]

        sample_metrics: dict[str, float] = {}

        # Faithfulness: 검색 결과와 질문의 의미적 일치도
        if "faithfulness" in metrics_to_eval and retrieved_contents:
            if use_llm_judge:
                score = _llm_judge_faithfulness(question, retrieved_contents)
            else:
                score = _compute_faithfulness(question, retrieved_contents)
            sample_metrics["faithfulness"] = score
            metric_sums["faithfulness"] += score
            metric_counts["faithfulness"] += 1

        # Context Relevancy: 검색된 컨텍스트 ↔ 질문 관련도
        if "context_relevancy" in metrics_to_eval and retrieved_contents:
            if use_llm_judge:
                score = _llm_judge_context_relevancy(question, retrieved_contents)
            else:
                score = _compute_context_relevancy(question, retrieved_contents)
            sample_metrics["context_relevancy"] = score
            metric_sums["context_relevancy"] += score
            metric_counts["context_relevancy"] += 1

        # Answer Relevancy: 답변(=검색결과 결합) ↔ 질문 관련도
        if "answer_relevancy" in metrics_to_eval and retrieved_contents:
            if use_llm_judge:
                score = _llm_judge_answer_relevancy(question, retrieved_contents)
            else:
                score = _compute_answer_relevancy(question, retrieved_contents)
            sample_metrics["answer_relevancy"] = score
            metric_sums["answer_relevancy"] += score
            metric_counts["answer_relevancy"] += 1

        # Context Precision: 기대 컨텍스트 대비 정밀도
        if "context_precision" in metrics_to_eval and expected_contexts:
            score = _compute_context_precision(retrieved_contents, expected_contexts)
            sample_metrics["context_precision"] = score
            metric_sums["context_precision"] += score
            metric_counts["context_precision"] += 1

        # Context Recall: ground_truth 포함 비율
        if "context_recall" in metrics_to_eval and ground_truth:
            score = _compute_context_recall(retrieved_contents, ground_truth)
            sample_metrics["context_recall"] = score
            metric_sums["context_recall"] += score
            metric_counts["context_recall"] += 1

        sample_results.append({
            "question": question,
            "retrieved_count": len(retrieved_contents),
            "metrics": sample_metrics,
        })

    # 평균 메트릭 계산
    avg_metrics = {}
    for m in metrics_to_eval:
        if metric_counts[m] > 0:
            avg_metrics[m] = round(metric_sums[m] / metric_counts[m], 4)

    elapsed = round(time.time() - start_time, 2)
    logger.info("RAGAS evaluation (mode=%s): %d samples, metrics=%s, %.1fs",
                mode, len(sample_results), avg_metrics, elapsed)

    return {
        "metrics": avg_metrics,
        "samples": sample_results,
        "sample_count": len(sample_results),
        "mode": mode,
        "elapsed_seconds": elapsed,
        "success": True,
    }


# ── 메트릭 계산 함수 ──────────────────────────────────────────────


def _compute_faithfulness(question: str, contexts: list[str]) -> float:
    """질문과 검색 결과의 시맨틱 유사도 평균."""
    try:
        q_embed = embed_texts([question])["embeddings"][0]
        c_embeds = embed_texts(contexts)["embeddings"]
        scores = [_cosine_sim(q_embed, ce) for ce in c_embeds]
        return sum(scores) / len(scores) if scores else 0.0
    except Exception:
        return 0.0


def _compute_context_relevancy(question: str, contexts: list[str]) -> float:
    """검색된 컨텍스트 중 질문과 유사도 임계값(0.3) 이상인 비율."""
    try:
        q_embed = embed_texts([question])["embeddings"][0]
        c_embeds = embed_texts(contexts)["embeddings"]
        relevant = sum(1 for ce in c_embeds if _cosine_sim(q_embed, ce) >= 0.3)
        return relevant / len(c_embeds) if c_embeds else 0.0
    except Exception:
        return 0.0


def _compute_answer_relevancy(question: str, contexts: list[str]) -> float:
    """결합된 답변(컨텍스트 연결)과 질문의 유사도."""
    try:
        combined = " ".join(contexts[:3])[:2000]
        embeds = embed_texts([question, combined])["embeddings"]
        return _cosine_sim(embeds[0], embeds[1])
    except Exception:
        return 0.0


def _compute_context_precision(retrieved: list[str], expected: list[str]) -> float:
    """검색 결과 중 기대 컨텍스트와 부분 매칭되는 비율."""
    if not retrieved or not expected:
        return 0.0
    hits = 0
    for r in retrieved:
        for e in expected:
            if e.lower() in r.lower() or r.lower() in e.lower():
                hits += 1
                break
    return hits / len(retrieved)


def _compute_context_recall(retrieved: list[str], ground_truth: str) -> float:
    """ground_truth의 핵심 키워드가 검색 결과에 포함된 비율."""
    if not ground_truth or not retrieved:
        return 0.0
    gt_words = set(w for w in ground_truth.split() if len(w) > 1)
    if not gt_words:
        return 0.0
    combined = " ".join(retrieved).lower()
    found = sum(1 for w in gt_words if w.lower() in combined)
    return found / len(gt_words)


def _cosine_sim(a: list[float], b: list[float]) -> float:
    """코사인 유사도 계산."""
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = sum(x * x for x in a) ** 0.5
    norm_b = sum(x * x for x in b) ** 0.5
    if norm_a == 0 or norm_b == 0:
        return 0.0
    return dot / (norm_a * norm_b)


# ── LLM Judge 모드 (CR-016) ─────────────────────────────────────────


def _llm_judge_faithfulness(question: str, contexts: list[str], answer: str = "") -> float:
    """LLM이 답변/검색결과가 컨텍스트에 근거하는지 판단."""
    if not contexts:
        return 0.0
    combined_context = "\n---\n".join(contexts[:3])
    answer_text = answer or combined_context[:500]

    # CR-036: DB 외부화
    from rag_pipeline import prompt_client
    prompt = prompt_client.render("eval.faithfulness.judge",
        {"context": combined_context[:2000], "answer": answer_text[:1000]},
        fallback=f"Score 0.0-1.0 how faithful the answer is to the context.\n\nContext:\n{combined_context[:2000]}\n\nAnswer:\n{answer_text[:1000]}\n\nScore:"
    )

    try:
        score = _call_llm_for_score(prompt)
        return score
    except Exception:
        return 0.0


def _llm_judge_context_relevancy(question: str, contexts: list[str]) -> float:
    """LLM이 검색된 컨텍스트가 질문에 관련있는지 판단."""
    if not contexts:
        return 0.0
    combined = "\n---\n".join(contexts[:3])

    # CR-036: DB 외부화
    from rag_pipeline import prompt_client
    prompt = prompt_client.render("eval.context_relevancy.judge",
        {"question": question, "context": combined[:2000]},
        fallback=f"Score 0.0-1.0 how relevant the context is.\n\nQuestion:\n{question}\n\nContext:\n{combined[:2000]}\n\nScore:"
    )

    try:
        return _call_llm_for_score(prompt)
    except Exception:
        return 0.0


def _llm_judge_answer_relevancy(question: str, contexts: list[str]) -> float:
    """LLM이 답변이 질문에 적절한지 판단."""
    if not contexts:
        return 0.0
    answer = " ".join(contexts[:3])[:2000]

    # CR-036: DB 외부화
    from rag_pipeline import prompt_client
    prompt = prompt_client.render("eval.answer_relevancy.judge",
        {"question": question, "answer": answer},
        fallback=f"Score 0.0-1.0 how appropriate the answer is.\n\nQuestion:\n{question}\n\nAnswer:\n{answer}\n\nScore:"
    )

    try:
        return _call_llm_for_score(prompt)
    except Exception:
        return 0.0


def _call_llm_for_score(prompt: str) -> float:
    """LLM API를 호출하여 점수를 받아옴. Anthropic Claude 사용."""
    import os

    import httpx

    api_key = os.getenv("ANTHROPIC_API_KEY", "")
    if not api_key:
        # Fallback: try to read from DB or config
        from rag_pipeline.db import get_connection_api_key
        api_key = get_connection_api_key()

    if not api_key:
        logger.warning("No LLM API key available for LLM Judge mode")
        return 0.0

    response = httpx.post(
        "https://api.anthropic.com/v1/messages",
        headers={
            "x-api-key": api_key,
            "anthropic-version": "2023-06-01",
            "content-type": "application/json",
        },
        json={
            "model": "claude-haiku-4-5-20251001",
            "max_tokens": 10,
            "messages": [{"role": "user", "content": prompt}],
        },
        timeout=30.0,
    )
    response.raise_for_status()
    text = response.json()["content"][0]["text"].strip()
    # Extract float from response
    match = re.search(r"(\d+\.?\d*)", text)
    if match:
        score = float(match.group(1))
        return min(max(score, 0.0), 1.0)
    return 0.0
