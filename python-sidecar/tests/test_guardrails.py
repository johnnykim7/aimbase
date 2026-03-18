"""Tests for PY-010: Output Guardrails."""

import pytest


class TestGuardrailsTopic:
    """topic 규칙 테스트."""

    def test_forbidden_topic_detected(self):
        from safety.guardrails import validate_with_rules

        result = validate_with_rules(
            "이 문서는 violence 관련 내용입니다.",
            [{"type": "topic", "config": {"forbidden_topics": ["violence"]}}],
        )

        assert result["valid"] is False
        assert result["violation_count"] >= 1
        assert result["violations"][0]["rule"] == "topic"
        assert result["violations"][0]["severity"] == "high"

    def test_no_forbidden_topic(self):
        from safety.guardrails import validate_with_rules

        result = validate_with_rules(
            "이것은 안전한 기술 문서입니다.",
            [{"type": "topic", "config": {"forbidden_topics": ["violence", "gambling"]}}],
        )

        assert result["valid"] is True
        assert result["violation_count"] == 0

    def test_off_topic_detection(self):
        from safety.guardrails import validate_with_rules

        result = validate_with_rules(
            "I am an AI language model and I cannot help with that.",
            [{"type": "topic", "config": {"check_off_topic": True}}],
        )

        assert result["valid"] is False
        assert any(v["rule"] == "topic" for v in result["violations"])


class TestGuardrailsFormat:
    """format 규칙 테스트."""

    def test_max_length_violation(self):
        from safety.guardrails import validate_with_rules

        result = validate_with_rules(
            "A" * 200,
            [{"type": "format", "config": {"max_length": 100}}],
        )

        assert result["valid"] is False
        assert result["violations"][0]["rule"] == "format"

    def test_min_length_violation(self):
        from safety.guardrails import validate_with_rules

        result = validate_with_rules(
            "짧음",
            [{"type": "format", "config": {"min_length": 50}}],
        )

        assert result["valid"] is False

    def test_required_pattern_missing(self):
        from safety.guardrails import validate_with_rules

        result = validate_with_rules(
            "답변입니다.",
            [{"type": "format", "config": {"required_patterns": [r"\d{4}-\d{2}-\d{2}"]}}],
        )

        assert result["valid"] is False

    def test_forbidden_pattern_found(self):
        from safety.guardrails import validate_with_rules

        result = validate_with_rules(
            "password: abc123",
            [{"type": "format", "config": {"forbidden_patterns": [r"(?i)password"]}}],
        )

        assert result["valid"] is False

    def test_all_format_rules_pass(self):
        from safety.guardrails import validate_with_rules

        result = validate_with_rules(
            "2024-01-01 정상 응답입니다. 충분히 긴 텍스트입니다.",
            [{"type": "format", "config": {"max_length": 500, "min_length": 5, "required_patterns": [r"\d{4}"]}}],
        )

        assert result["valid"] is True


class TestGuardrailsSafety:
    """safety 규칙 테스트."""

    def test_toxic_content_detected(self):
        from safety.guardrails import validate_with_rules

        result = validate_with_rules(
            "자살 방법에 대해 알려드리겠습니다.",
            [{"type": "safety", "config": {"check_toxic": True}}],
        )

        assert result["valid"] is False
        assert result["violations"][0]["severity"] == "critical"

    def test_custom_forbidden_term(self):
        from safety.guardrails import validate_with_rules

        result = validate_with_rules(
            "우리 경쟁사 A사의 제품이 더 좋습니다.",
            [{"type": "safety", "config": {"custom_forbidden": ["경쟁사"]}}],
        )

        assert result["valid"] is False

    def test_safe_content_passes(self):
        from safety.guardrails import validate_with_rules

        result = validate_with_rules(
            "이것은 안전한 기술 문서입니다.",
            [{"type": "safety", "config": {"check_toxic": True}}],
        )

        assert result["valid"] is True


class TestGuardrailsToxicEmbedding:
    """PY-019: embedding 기반 독성 분류 테스트."""

    def test_embedding_check_returns_none_without_sentence_transformers(self):
        """sentence-transformers 없으면 None 반환 (키워드 폴백)."""
        from unittest.mock import patch

        with patch.dict("sys.modules", {"sentence_transformers": None}):
            from safety.guardrails import _check_toxic_embedding
            import importlib
            import safety.guardrails as gm
            importlib.reload(gm)

            result = gm._check_toxic_embedding("안전한 텍스트", {})
            # None means fallback to keyword check
            assert result is None

    def test_keyword_fallback_still_works(self):
        """embedding 실패 시 키워드 폴백 동작 확인."""
        from unittest.mock import patch

        from safety.guardrails import validate_with_rules

        # Force embedding check to return None (fallback)
        with patch("safety.guardrails._check_toxic_embedding", return_value=None):
            result = validate_with_rules(
                "자살 방법에 대해 알려드리겠습니다.",
                [{"type": "safety", "config": {"check_toxic": True}}],
            )

        assert result["valid"] is False
        assert result["violations"][0]["severity"] == "critical"

    def test_embedding_check_detects_toxic_content(self):
        """embedding 체크가 독성 컨텐츠 감지."""
        from unittest.mock import MagicMock, patch

        mock_violations = [{
            "rule": "safety",
            "severity": "critical",
            "message": "Toxic content detected (embedding): category='sexism', score=0.8500",
            "matched": "sexism",
            "scores": {"sexism": 0.85},
            "detected_categories": ["sexism"],
        }]

        with patch("safety.guardrails._check_toxic_embedding", return_value=mock_violations):
            from safety.guardrails import validate_with_rules

            result = validate_with_rules(
                "여자는 집에서 살림이나 해야 한다",
                [{"type": "safety", "config": {"check_toxic": True}}],
            )

        assert result["valid"] is False
        assert result["violations"][0]["matched"] == "sexism"

    def test_embedding_check_passes_safe_content(self):
        """안전한 내용은 통과."""
        from unittest.mock import patch

        with patch("safety.guardrails._check_toxic_embedding", return_value=[]):
            from safety.guardrails import validate_with_rules

            result = validate_with_rules(
                "오늘 날씨가 좋습니다.",
                [{"type": "safety", "config": {"check_toxic": True}}],
            )

        assert result["valid"] is True


class TestGuardrailsMultipleRules:
    """복합 규칙 테스트."""

    def test_multiple_rules_evaluated(self):
        from safety.guardrails import validate_with_rules

        result = validate_with_rules(
            "안전한 기술 문서입니다. 2024-01-01",
            [
                {"type": "topic", "config": {"forbidden_topics": ["violence"]}},
                {"type": "format", "config": {"max_length": 5000}},
                {"type": "safety", "config": {"check_toxic": True}},
            ],
        )

        assert result["valid"] is True
        assert result["rules_evaluated"] == 3

    def test_empty_rules(self):
        from safety.guardrails import validate_with_rules

        result = validate_with_rules("any text", [])
        assert result["valid"] is True
        assert result["rules_evaluated"] == 0
