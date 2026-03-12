"""TC-PY-SAFE-002: PII detection engine integration tests.

Verifies detect_pii, mask_pii, validate_output functions
with mixed Korean PII types.
"""

import pytest

from safety.engine import detect_pii, mask_pii, validate_output


class TestDetectPii:
    """TC-PY-SAFE-002a: PII 탐지 통합 테스트."""

    def test_detect_rrn(self):
        """주민등록번호 탐지."""
        text = "고객 주민등록번호: 901234-1234567"
        results = detect_pii(text, language="ko")
        entity_types = {r["entity_type"] for r in results}
        assert "KR_RRN" in entity_types

    def test_detect_phone(self):
        """휴대폰번호 탐지."""
        text = "연락처: 010-9876-5432"
        results = detect_pii(text, language="ko")
        entity_types = {r["entity_type"] for r in results}
        assert "KR_PHONE_NUMBER" in entity_types

    def test_detect_multiple_pii(self):
        """여러 PII 동시 탐지."""
        text = "이름: 홍길동, 주민번호: 880101-1234567, 연락처: 010-1234-5678, 이메일: hong@example.com"
        results = detect_pii(text, language="ko")
        entity_types = {r["entity_type"] for r in results}
        assert "KR_RRN" in entity_types
        assert "KR_PHONE_NUMBER" in entity_types

    def test_no_pii_clean_text(self):
        """PII가 없는 텍스트."""
        text = "오늘 날씨가 좋습니다. 산책하기 좋은 날이네요."
        results = detect_pii(text, language="ko")
        assert len(results) == 0

    def test_result_structure(self):
        """탐지 결과 구조 검증."""
        text = "번호: 901234-1234567"
        results = detect_pii(text, language="ko")
        assert len(results) >= 1
        result = results[0]
        assert "entity_type" in result
        assert "start" in result
        assert "end" in result
        assert "score" in result
        assert "text" in result


class TestMaskPii:
    """TC-PY-SAFE-002b: PII 마스킹 통합 테스트."""

    def test_mask_rrn(self):
        """주민등록번호 마스킹."""
        text = "주민번호: 901234-1234567"
        result = mask_pii(text, language="ko")
        assert result["pii_found"] is True
        assert "901234-1234567" not in result["masked_text"]

    def test_mask_phone(self):
        """휴대폰번호 마스킹."""
        text = "전화: 010-1234-5678"
        result = mask_pii(text, language="ko")
        assert result["pii_found"] is True
        assert "010-1234-5678" not in result["masked_text"]

    def test_mask_preserves_non_pii(self):
        """PII가 아닌 텍스트는 보존."""
        text = "안녕하세요. 주문번호 A12345 확인 부탁드립니다."
        result = mask_pii(text, language="ko")
        # No PII → text should be unchanged
        if not result["pii_found"]:
            assert result["masked_text"] == text

    def test_mask_mixed_pii(self):
        """복합 PII 마스킹."""
        text = "홍길동(주민번호: 880101-1234567, 전화: 010-9876-5432)"
        result = mask_pii(text, language="ko")
        assert result["pii_found"] is True
        assert "880101-1234567" not in result["masked_text"]
        assert "010-9876-5432" not in result["masked_text"]
        assert len(result["detections"]) >= 2

    def test_mask_result_structure(self):
        """마스킹 결과 구조 검증."""
        text = "연락처: 010-1234-5678"
        result = mask_pii(text, language="ko")
        assert "masked_text" in result
        assert "detections" in result
        assert "pii_found" in result


class TestValidateOutput:
    """TC-PY-SAFE-002c: LLM 출력 검증 테스트."""

    def test_safe_output(self):
        """PII 없는 안전한 출력."""
        text = "주문이 정상적으로 처리되었습니다. 감사합니다."
        result = validate_output(text, language="ko")
        assert result["safe"] is True
        assert result["violation_count"] == 0

    def test_unsafe_output_with_rrn(self):
        """주민번호 포함 출력은 unsafe."""
        text = "고객님의 주민번호는 901234-1234567입니다."
        result = validate_output(text, language="ko")
        assert result["safe"] is False
        assert result["violation_count"] >= 1

    def test_unsafe_output_with_phone(self):
        """전화번호 포함 출력은 unsafe."""
        text = "고객님의 연락처는 010-1234-5678입니다."
        result = validate_output(text, language="ko")
        assert result["safe"] is False

    def test_validate_result_structure(self):
        """검증 결과 구조."""
        text = "테스트 텍스트입니다."
        result = validate_output(text, language="ko")
        assert "safe" in result
        assert "violations" in result
        assert "violation_count" in result
