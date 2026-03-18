"""PY-010: Output Guardrails Engine.

Rules-based output validation: topic adherence, format enforcement, safety checks.
Integrates with existing PII detection as one of the safety checks.
"""

import logging
import re
from typing import Any

logger = logging.getLogger(__name__)

# 유해 키워드 (한국어 + 영어)
_TOXIC_KEYWORDS = [
    # 한국어 유해 표현
    "자살", "자해", "폭발물", "마약", "살인 방법", "해킹 방법",
    # 영어 유해 표현
    "how to make a bomb", "how to hack", "suicide method",
]

# 일반적인 주제 이탈 감지 패턴
_OFF_TOPIC_PATTERNS = [
    r"나는\s+AI",
    r"I am an AI",
    r"as an AI language model",
    r"I cannot",
    r"I'm sorry.+I can't",
]


def validate_with_rules(
    text: str,
    rules: list[dict[str, Any]],
) -> dict[str, Any]:
    """Validate LLM output against a set of guardrail rules.

    Args:
        text: LLM output text to validate
        rules: List of rule definitions:
            - {"type": "topic", "config": {"allowed_topics": [...], "forbidden_topics": [...]}}
            - {"type": "format", "config": {"max_length": N, "required_patterns": [...], "forbidden_patterns": [...]}}
            - {"type": "safety", "config": {"check_toxic": true, "check_pii": true, "custom_forbidden": [...]}}

    Returns:
        {"valid": True/False, "violations": [...], "violation_count": N, "rules_evaluated": N}
    """
    violations: list[dict[str, Any]] = []
    rules_evaluated = 0

    for rule in rules:
        rule_type = rule.get("type", "").lower()
        config = rule.get("config", {})
        rules_evaluated += 1

        if rule_type == "topic":
            violations.extend(_check_topic(text, config))
        elif rule_type == "format":
            violations.extend(_check_format(text, config))
        elif rule_type == "safety":
            violations.extend(_check_safety(text, config))
        else:
            logger.warning("Unknown guardrail rule type: %s", rule_type)

    return {
        "valid": len(violations) == 0,
        "violations": violations,
        "violation_count": len(violations),
        "rules_evaluated": rules_evaluated,
    }


def _check_topic(text: str, config: dict[str, Any]) -> list[dict[str, Any]]:
    """Check topic adherence."""
    violations = []
    text_lower = text.lower()

    # Forbidden topics
    forbidden_topics = config.get("forbidden_topics", [])
    for topic in forbidden_topics:
        if topic.lower() in text_lower:
            violations.append({
                "rule": "topic",
                "severity": "high",
                "message": f"Forbidden topic detected: '{topic}'",
                "matched": topic,
            })

    # Off-topic detection (generic patterns)
    if config.get("check_off_topic", False):
        for pattern in _OFF_TOPIC_PATTERNS:
            if re.search(pattern, text, re.IGNORECASE):
                violations.append({
                    "rule": "topic",
                    "severity": "medium",
                    "message": f"Off-topic response pattern detected",
                    "matched": pattern,
                })
                break

    return violations


def _check_format(text: str, config: dict[str, Any]) -> list[dict[str, Any]]:
    """Check format constraints."""
    violations = []

    # Max length
    max_length = config.get("max_length")
    if max_length and len(text) > max_length:
        violations.append({
            "rule": "format",
            "severity": "low",
            "message": f"Output exceeds max length: {len(text)} > {max_length}",
            "matched": f"length={len(text)}",
        })

    # Min length
    min_length = config.get("min_length")
    if min_length and len(text) < min_length:
        violations.append({
            "rule": "format",
            "severity": "low",
            "message": f"Output below min length: {len(text)} < {min_length}",
            "matched": f"length={len(text)}",
        })

    # Required patterns (must be present)
    required_patterns = config.get("required_patterns", [])
    for pattern in required_patterns:
        if not re.search(pattern, text):
            violations.append({
                "rule": "format",
                "severity": "medium",
                "message": f"Required pattern not found: '{pattern}'",
                "matched": pattern,
            })

    # Forbidden patterns (must not be present)
    forbidden_patterns = config.get("forbidden_patterns", [])
    for pattern in forbidden_patterns:
        match = re.search(pattern, text)
        if match:
            violations.append({
                "rule": "format",
                "severity": "medium",
                "message": f"Forbidden pattern found: '{pattern}'",
                "matched": match.group(),
            })

    return violations


def _check_safety(text: str, config: dict[str, Any]) -> list[dict[str, Any]]:
    """Check safety constraints (toxic content, PII, custom forbidden terms).

    Enhanced with embedding-based toxic classification (PY-019).
    Falls back to keyword-based check when sentence-transformers is unavailable.
    """
    violations = []
    text_lower = text.lower()

    # Toxic content check
    if config.get("check_toxic", True):
        # Try embedding-based classification first (PY-019)
        embedding_result = _check_toxic_embedding(text, config)
        if embedding_result is not None:
            violations.extend(embedding_result)
        else:
            # Fallback: keyword-based check
            for keyword in _TOXIC_KEYWORDS:
                if keyword.lower() in text_lower:
                    violations.append({
                        "rule": "safety",
                        "severity": "critical",
                        "message": f"Potentially harmful content detected: '{keyword}'",
                        "matched": keyword,
                    })

    # PII check (delegate to existing PII engine)
    if config.get("check_pii", False):
        try:
            from safety.engine import detect_pii

            detections = detect_pii(text)
            if detections:
                violations.append({
                    "rule": "safety",
                    "severity": "high",
                    "message": f"PII detected in output: {len(detections)} entities",
                    "matched": ", ".join(d["entity_type"] for d in detections),
                })
        except Exception as e:
            logger.warning("PII check failed: %s", e)

    # Custom forbidden terms
    custom_forbidden = config.get("custom_forbidden", [])
    for term in custom_forbidden:
        if term.lower() in text_lower:
            violations.append({
                "rule": "safety",
                "severity": "high",
                "message": f"Custom forbidden term detected: '{term}'",
                "matched": term,
            })

    return violations


def _check_toxic_embedding(
    text: str,
    config: dict[str, Any],
) -> list[dict[str, Any]] | None:
    """Embedding-based toxic content classification (PY-019).

    Uses sentence-transformers to compute cosine similarity between input text
    and reference toxic sentences. Returns None if sentence-transformers
    is unavailable, signaling the caller to use keyword fallback.

    Args:
        text: Input text to check
        config: Safety config with optional "toxic_threshold" (default: 0.7)

    Returns:
        List of violations if embedding check succeeded, None if unavailable.
    """
    try:
        from sentence_transformers import SentenceTransformer, util as st_util
    except ImportError:
        logger.debug("sentence-transformers not available, falling back to keyword check")
        return None

    try:
        from safety.toxic_references import TOXIC_REFERENCES, DEFAULT_TOXIC_THRESHOLD

        threshold = config.get("toxic_threshold", DEFAULT_TOXIC_THRESHOLD)

        # Lazy-load model
        model = _get_toxic_model()
        if model is None:
            return None

        # Encode input text
        text_embedding = model.encode(text, convert_to_tensor=True)

        violations: list[dict[str, Any]] = []
        scores: dict[str, float] = {}
        detected_categories: list[str] = []

        for category, references in TOXIC_REFERENCES.items():
            ref_embeddings = model.encode(references, convert_to_tensor=True)
            similarities = st_util.cos_sim(text_embedding, ref_embeddings)[0]
            max_sim = float(similarities.max())
            scores[category] = round(max_sim, 4)

            if max_sim > threshold:
                detected_categories.append(category)
                violations.append({
                    "rule": "safety",
                    "severity": "critical",
                    "message": f"Toxic content detected (embedding): category='{category}', score={max_sim:.4f}",
                    "matched": category,
                    "scores": scores.copy(),
                    "detected_categories": detected_categories.copy(),
                })

        return violations

    except Exception as e:
        logger.warning("Embedding-based toxic check failed: %s", e)
        return None


# Module-level model cache for toxic classification
_toxic_model_cache = None


def _get_toxic_model():
    """Lazy-load and cache sentence-transformers model for toxic classification."""
    global _toxic_model_cache
    if _toxic_model_cache is not None:
        return _toxic_model_cache
    try:
        from sentence_transformers import SentenceTransformer

        _toxic_model_cache = SentenceTransformer("sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2")
        return _toxic_model_cache
    except Exception as e:
        logger.warning("Failed to load toxic classification model: %s", e)
        return None
