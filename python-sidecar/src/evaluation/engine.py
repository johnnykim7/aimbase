"""Evaluation Engine.

RAG 품질 평가 (RAGAS) + LLM 출력 평가 (DeepEval) + 프롬프트 회귀 테스트.
"""

import logging
import re
from typing import Any

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# PY-006: RAG 품질 평가 (RAGAS)
# ---------------------------------------------------------------------------

def evaluate_rag(
    question: str,
    answer: str,
    contexts: list[str],
    ground_truth: str | None = None,
) -> dict[str, Any]:
    """RAG 응답 품질을 RAGAS 메트릭으로 평가.

    Metrics:
        - faithfulness: 답변이 제공된 컨텍스트에 충실한 정도
        - answer_relevancy: 답변이 질문과 관련된 정도
        - context_precision: 컨텍스트 중 관련 정보의 비율
        - context_recall: ground_truth 대비 컨텍스트 커버리지 (ground_truth 필요)

    Returns:
        {faithfulness: float, relevancy: float, context_precision: float,
         context_recall: float | None}
    """
    try:
        return _evaluate_rag_ragas(question, answer, contexts, ground_truth)
    except ImportError:
        logger.warning("RAGAS not available — using heuristic evaluation")
        return _evaluate_rag_heuristic(question, answer, contexts, ground_truth)


def _evaluate_rag_ragas(
    question: str,
    answer: str,
    contexts: list[str],
    ground_truth: str | None,
) -> dict[str, Any]:
    """RAGAS 라이브러리를 사용한 RAG 평가."""
    from ragas import evaluate as ragas_evaluate
    from ragas.metrics import (
        answer_relevancy,
        context_precision,
        context_recall,
        faithfulness,
    )
    from datasets import Dataset

    # RAGAS Dataset 구성
    data = {
        "question": [question],
        "answer": [answer],
        "contexts": [contexts],
    }

    metrics = [faithfulness, answer_relevancy, context_precision]

    if ground_truth:
        data["ground_truth"] = [ground_truth]
        metrics.append(context_recall)

    dataset = Dataset.from_dict(data)
    result = ragas_evaluate(dataset, metrics=metrics)

    scores = result.to_pandas().iloc[0].to_dict()

    return {
        "faithfulness": _clamp(scores.get("faithfulness")),
        "relevancy": _clamp(scores.get("answer_relevancy")),
        "context_precision": _clamp(scores.get("context_precision")),
        "context_recall": _clamp(scores.get("context_recall")) if ground_truth else None,
    }


def _evaluate_rag_heuristic(
    question: str,
    answer: str,
    contexts: list[str],
    ground_truth: str | None,
) -> dict[str, Any]:
    """RAGAS 없이 키워드 오버랩 기반 휴리스틱 평가 (폴백)."""
    q_words = set(question.lower().split())
    a_words = set(answer.lower().split())
    c_words = set()
    for ctx in contexts:
        c_words.update(ctx.lower().split())

    # faithfulness: 답변 단어가 컨텍스트에 포함된 비율
    faithfulness = len(a_words & c_words) / max(len(a_words), 1)

    # relevancy: 답변 단어가 질문 단어와 겹치는 비율
    relevancy = len(a_words & q_words) / max(len(q_words), 1)

    # context_precision: 컨텍스트 단어가 질문과 겹치는 비율
    context_precision = len(c_words & q_words) / max(len(q_words), 1)

    # context_recall: ground_truth 단어가 컨텍스트에 포함된 비율
    context_recall = None
    if ground_truth:
        gt_words = set(ground_truth.lower().split())
        context_recall = _clamp(len(gt_words & c_words) / max(len(gt_words), 1))

    return {
        "faithfulness": _clamp(faithfulness),
        "relevancy": _clamp(relevancy),
        "context_precision": _clamp(context_precision),
        "context_recall": context_recall,
    }


# ---------------------------------------------------------------------------
# PY-007: LLM 출력 평가 (DeepEval)
# ---------------------------------------------------------------------------

# 지원 메트릭
SUPPORTED_METRICS = {"hallucination", "toxicity", "bias"}


def evaluate_llm_output(
    input_text: str,
    output_text: str,
    context: str | None = None,
    metrics: list[str] | None = None,
) -> dict[str, Any]:
    """LLM 출력에서 환각, 독성, 편향을 평가.

    Args:
        input_text: 사용자 입력(프롬프트)
        output_text: LLM 출력
        context: 참조 컨텍스트 (환각 탐지용)
        metrics: 평가할 메트릭 목록 (default: all)

    Returns:
        {hallucination_score: float, toxicity_score: float,
         bias_score: float, details: {}}
    """
    target_metrics = _resolve_metrics(metrics)

    try:
        return _evaluate_llm_deepeval(input_text, output_text, context, target_metrics)
    except ImportError:
        logger.warning("DeepEval not available — using heuristic evaluation")
        return _evaluate_llm_heuristic(input_text, output_text, context, target_metrics)


def _evaluate_llm_deepeval(
    input_text: str,
    output_text: str,
    context: str | None,
    target_metrics: set[str],
) -> dict[str, Any]:
    """DeepEval 라이브러리를 사용한 LLM 출력 평가."""
    from deepeval.metrics import (
        BiasMetric,
        HallucinationMetric,
        ToxicityMetric,
    )
    from deepeval.test_case import LLMTestCase

    from evaluation.config import settings

    test_case = LLMTestCase(
        input=input_text,
        actual_output=output_text,
        context=[context] if context else None,
    )

    results: dict[str, Any] = {
        "hallucination_score": 0.0,
        "toxicity_score": 0.0,
        "bias_score": 0.0,
        "details": {},
    }

    threshold = settings.DEEPEVAL_DEFAULT_THRESHOLD

    if "hallucination" in target_metrics and context:
        metric = HallucinationMetric(threshold=threshold)
        metric.measure(test_case)
        results["hallucination_score"] = _clamp(metric.score)
        results["details"]["hallucination"] = {
            "score": _clamp(metric.score),
            "reason": metric.reason,
            "passed": metric.is_successful(),
        }

    if "toxicity" in target_metrics:
        metric = ToxicityMetric(threshold=threshold)
        metric.measure(test_case)
        results["toxicity_score"] = _clamp(metric.score)
        results["details"]["toxicity"] = {
            "score": _clamp(metric.score),
            "reason": metric.reason,
            "passed": metric.is_successful(),
        }

    if "bias" in target_metrics:
        metric = BiasMetric(threshold=threshold)
        metric.measure(test_case)
        results["bias_score"] = _clamp(metric.score)
        results["details"]["bias"] = {
            "score": _clamp(metric.score),
            "reason": metric.reason,
            "passed": metric.is_successful(),
        }

    return results


def _evaluate_llm_heuristic(
    input_text: str,
    output_text: str,
    context: str | None,
    target_metrics: set[str],
) -> dict[str, Any]:
    """DeepEval 없이 키워드 기반 휴리스틱 평가 (폴백)."""
    results: dict[str, Any] = {
        "hallucination_score": 0.0,
        "toxicity_score": 0.0,
        "bias_score": 0.0,
        "details": {},
    }

    if "hallucination" in target_metrics and context:
        # 출력 단어 중 컨텍스트에 없는 비율 → 환각 점수
        out_words = set(output_text.lower().split())
        ctx_words = set(context.lower().split())
        if out_words:
            unsupported = len(out_words - ctx_words) / len(out_words)
            results["hallucination_score"] = _clamp(unsupported)
        results["details"]["hallucination"] = {
            "score": results["hallucination_score"],
            "reason": "heuristic: word overlap with context",
            "passed": results["hallucination_score"] < 0.5,
        }

    if "toxicity" in target_metrics:
        toxic_keywords = {
            "죽", "바보", "멍청", "시발", "씨발", "개새끼", "병신",
            "kill", "hate", "stupid", "idiot", "fuck", "shit", "damn",
            "violent", "die", "attack", "destroy",
        }
        out_lower = output_text.lower()
        matches = [kw for kw in toxic_keywords if kw in out_lower]
        score = min(len(matches) / 3.0, 1.0)
        results["toxicity_score"] = _clamp(score)
        results["details"]["toxicity"] = {
            "score": results["toxicity_score"],
            "reason": f"heuristic: {len(matches)} toxic keyword(s) found",
            "passed": results["toxicity_score"] < 0.5,
        }

    if "bias" in target_metrics:
        bias_keywords = {
            "항상", "모든", "절대", "never", "always", "all",
            "every", "none", "only", "무조건",
        }
        out_lower = output_text.lower()
        matches = [kw for kw in bias_keywords if kw in out_lower]
        score = min(len(matches) / 3.0, 1.0)
        results["bias_score"] = _clamp(score)
        results["details"]["bias"] = {
            "score": results["bias_score"],
            "reason": f"heuristic: {len(matches)} bias indicator(s) found",
            "passed": results["bias_score"] < 0.5,
        }

    return results


# ---------------------------------------------------------------------------
# PY-008: 프롬프트 회귀 테스트
# ---------------------------------------------------------------------------

def compare_prompts(
    test_cases: list[dict[str, str]],
    prompt_a: dict[str, Any],
    prompt_b: dict[str, Any],
    metrics: list[str] | None = None,
) -> dict[str, Any]:
    """두 프롬프트 버전의 품질을 A/B 비교.

    Args:
        test_cases: [{input: str, expected_output: str}, ...]
        prompt_a: {id: str, version: str, template: str}
        prompt_b: {id: str, version: str, template: str}
        metrics: 비교할 메트릭 목록 (default: ["relevancy", "faithfulness"])

    Returns:
        {summary: {prompt_a_avg, prompt_b_avg, improvement_pct},
         details: [{input, score_a, score_b}, ...]}
    """
    eval_metrics = metrics or ["relevancy", "faithfulness"]
    template_a = prompt_a.get("template", "")
    template_b = prompt_b.get("template", "")

    details: list[dict[str, Any]] = []
    total_a = 0.0
    total_b = 0.0

    for tc in test_cases:
        input_text = tc.get("input", "")
        expected = tc.get("expected_output", "")

        # 프롬프트 A 평가: 템플릿에 입력을 적용하여 생성된 응답을 평가
        answer_a = _apply_template(template_a, input_text)
        score_a = _compute_aggregate_score(
            question=input_text,
            answer=answer_a,
            contexts=[expected] if expected else [],
            ground_truth=expected,
            target_metrics=eval_metrics,
        )

        # 프롬프트 B 평가
        answer_b = _apply_template(template_b, input_text)
        score_b = _compute_aggregate_score(
            question=input_text,
            answer=answer_b,
            contexts=[expected] if expected else [],
            ground_truth=expected,
            target_metrics=eval_metrics,
        )

        details.append({
            "input": input_text,
            "score_a": round(score_a, 4),
            "score_b": round(score_b, 4),
        })
        total_a += score_a
        total_b += score_b

    n = max(len(test_cases), 1)
    avg_a = round(total_a / n, 4)
    avg_b = round(total_b / n, 4)
    improvement = round((avg_b - avg_a) / max(avg_a, 0.0001) * 100, 2)

    return {
        "summary": {
            "prompt_a_avg": avg_a,
            "prompt_b_avg": avg_b,
            "improvement_pct": improvement,
        },
        "details": details,
        "prompt_a": {"id": prompt_a.get("id"), "version": prompt_a.get("version")},
        "prompt_b": {"id": prompt_b.get("id"), "version": prompt_b.get("version")},
        "metrics": eval_metrics,
    }


# ---------------------------------------------------------------------------
# PY-020: RAG 벤치마크 자동 생성
# ---------------------------------------------------------------------------

# 정의형 패턴: "은/는 ...이다/입니다"
_DEFINITION_PATTERNS = [
    re.compile(r'(.+?)[은는]\s+(.+(?:이다|입니다|이에요|예요|다)[.!?]?)'),
]

# 설명형 패턴: "때문에/위해/하면"
_EXPLANATION_PATTERNS = [
    re.compile(r'(.+(?:때문에|위해|위하여|하면|해서|므로).+)'),
]


def generate_benchmark(
    chunks: list[dict[str, Any]],
    question_types: list[str] | None = None,
    max_questions: int = 20,
) -> dict[str, Any]:
    """청크에서 템플릿 기반 Q&A 벤치마크를 자동 생성 (LLM 불필요).

    Args:
        chunks: [{content: str, metadata?: dict}] 청크 목록
        question_types: 생성할 질문 유형 ["definition", "explanation", "comparison"]
        max_questions: 최대 생성 질문 수

    Returns:
        {benchmark: [{question, answer, contexts, ground_truth, question_type}], total: int}
    """
    import re as _re

    if question_types is None:
        question_types = ["definition", "explanation", "comparison"]

    if not chunks:
        return {"benchmark": [], "total": 0}

    benchmark: list[dict[str, Any]] = []

    for chunk in chunks:
        if len(benchmark) >= max_questions:
            break

        content = chunk.get("content", "")
        if not content.strip():
            continue

        contexts = [content]

        # Definition questions
        if "definition" in question_types and len(benchmark) < max_questions:
            for pattern in _DEFINITION_PATTERNS:
                for match in pattern.finditer(content):
                    if len(benchmark) >= max_questions:
                        break
                    subject = match.group(1).strip()
                    full_sentence = match.group(0).strip()
                    # 주어가 너무 짧거나 길면 스킵
                    if len(subject) < 2 or len(subject) > 50:
                        continue
                    benchmark.append({
                        "question": f"{subject}란 무엇인가?",
                        "answer": full_sentence,
                        "contexts": contexts,
                        "ground_truth": full_sentence,
                        "question_type": "definition",
                    })

        # Explanation questions
        if "explanation" in question_types and len(benchmark) < max_questions:
            for pattern in _EXPLANATION_PATTERNS:
                for match in pattern.finditer(content):
                    if len(benchmark) >= max_questions:
                        break
                    sentence = match.group(0).strip()
                    if len(sentence) < 10:
                        continue
                    # 문장에서 키워드 추출 (첫 명사구)
                    topic = sentence[:min(20, len(sentence))]
                    if "때문에" in sentence:
                        topic_end = sentence.index("때문에")
                        topic = sentence[:topic_end].strip()
                    elif "위해" in sentence:
                        topic_end = sentence.index("위해")
                        topic = sentence[:topic_end].strip()
                    elif "하면" in sentence:
                        topic_end = sentence.index("하면")
                        topic = sentence[:topic_end].strip()

                    if len(topic) < 2:
                        continue
                    benchmark.append({
                        "question": f"왜 {topic}인가?",
                        "answer": sentence,
                        "contexts": contexts,
                        "ground_truth": sentence,
                        "question_type": "explanation",
                    })

        # Comparison questions: 2+ entities in content
        if "comparison" in question_types and len(benchmark) < max_questions:
            entities = _extract_entities_simple(content)
            if len(entities) >= 2:
                for i in range(len(entities) - 1):
                    if len(benchmark) >= max_questions:
                        break
                    a, b = entities[i], entities[i + 1]
                    benchmark.append({
                        "question": f"{a}와 {b}의 차이는?",
                        "answer": content.strip(),
                        "contexts": contexts,
                        "ground_truth": content.strip(),
                        "question_type": "comparison",
                    })

    return {"benchmark": benchmark, "total": len(benchmark)}


def _extract_entities_simple(text: str) -> list[str]:
    """간단한 엔티티 추출: '은/는' 앞의 명사구를 엔티티로 간주."""
    import re as _re
    matches = _re.findall(r'(\S+)[은는]', text)
    # 중복 제거, 순서 유지
    seen: set[str] = set()
    entities: list[str] = []
    for m in matches:
        if m not in seen and len(m) >= 2:
            seen.add(m)
            entities.append(m)
    return entities


# ---------------------------------------------------------------------------
# 유틸리티
# ---------------------------------------------------------------------------

def _clamp(value: float | None, lo: float = 0.0, hi: float = 1.0) -> float | None:
    """값을 [lo, hi] 범위로 클램핑."""
    if value is None:
        return None
    return max(lo, min(hi, float(value)))


def _resolve_metrics(metrics: list[str] | None) -> set[str]:
    """요청된 메트릭을 검증하고 반환."""
    if not metrics:
        return SUPPORTED_METRICS.copy()
    resolved = set(m.lower() for m in metrics) & SUPPORTED_METRICS
    return resolved if resolved else SUPPORTED_METRICS.copy()


def _apply_template(template: str, input_text: str) -> str:
    """프롬프트 템플릿에 입력을 적용. {input} 플레이스홀더 치환."""
    if not template:
        return input_text
    try:
        return template.replace("{input}", input_text)
    except Exception:
        return input_text


def _compute_aggregate_score(
    question: str,
    answer: str,
    contexts: list[str],
    ground_truth: str | None,
    target_metrics: list[str],
) -> float:
    """RAG 평가 메트릭의 평균 점수를 계산."""
    rag_result = evaluate_rag(question, answer, contexts, ground_truth)
    scores = []

    metric_map = {
        "faithfulness": rag_result.get("faithfulness"),
        "relevancy": rag_result.get("relevancy"),
        "context_precision": rag_result.get("context_precision"),
        "context_recall": rag_result.get("context_recall"),
    }

    for m in target_metrics:
        val = metric_map.get(m)
        if val is not None:
            scores.append(val)

    return sum(scores) / max(len(scores), 1)
