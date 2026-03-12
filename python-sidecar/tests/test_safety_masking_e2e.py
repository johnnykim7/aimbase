"""TC-PY-SAFE-004: End-to-end PII masking scenario tests.

Simulates real-world Korean text inputs containing mixed PII,
verifying that all PII is properly detected and masked.
"""

import json
import pytest

from safety.engine import detect_pii, mask_pii, validate_output


class TestE2ECustomerServiceScenario:
    """TC-PY-SAFE-004a: 고객 서비스 시나리오."""

    def test_customer_inquiry_with_personal_info(self):
        """고객 문의에 포함된 개인정보 탐지 및 마스킹."""
        text = (
            "안녕하세요, 홍길동입니다. "
            "주민번호 880101-1234567이고, "
            "연락처는 010-9876-5432입니다. "
            "국민은행 계좌 123-45-6789-012로 환불 부탁드립니다."
        )

        # 탐지
        detections = detect_pii(text, language="ko")
        detected_types = {d["entity_type"] for d in detections}
        assert "KR_RRN" in detected_types
        assert "KR_PHONE_NUMBER" in detected_types

        # 마스킹
        masked = mask_pii(text, language="ko")
        assert masked["pii_found"] is True
        assert "880101-1234567" not in masked["masked_text"]
        assert "010-9876-5432" not in masked["masked_text"]

        # 마스킹 결과 재검증 — 마스킹된 텍스트에는 PII가 없어야 함
        validation = validate_output(masked["masked_text"], language="ko")
        assert validation["safe"] is True, (
            f"Masked text still contains PII: {validation['violations']}"
        )

    def test_llm_response_validation(self):
        """LLM 응답에 PII 누출 시 탐지."""
        safe_response = "고객님의 주문이 정상 처리되었습니다. 3~5일 내 배송됩니다."
        unsafe_response = "고객님의 주민번호 901234-1234567로 확인되었습니다."

        safe_result = validate_output(safe_response, language="ko")
        assert safe_result["safe"] is True

        unsafe_result = validate_output(unsafe_response, language="ko")
        assert unsafe_result["safe"] is False
        assert unsafe_result["violation_count"] >= 1


class TestE2EMultiplePiiTypes:
    """TC-PY-SAFE-004b: 다중 PII 타입 혼합 시나리오."""

    def test_email_and_korean_pii_mixed(self):
        """이메일 + 한국 PII 혼합 텍스트."""
        text = "이메일: hong@example.com, 전화: 010-1111-2222"
        result = mask_pii(text, language="ko")
        assert result["pii_found"] is True
        assert "hong@example.com" not in result["masked_text"]
        assert "010-1111-2222" not in result["masked_text"]

    def test_credit_card_detection(self):
        """신용카드 번호 탐지."""
        text = "카드번호: 1234-5678-9012-3456"
        result = mask_pii(text, language="ko")
        assert result["pii_found"] is True
        assert "1234-5678-9012-3456" not in result["masked_text"]

    def test_empty_text(self):
        """빈 텍스트 처리."""
        result = mask_pii("", language="ko")
        assert result["pii_found"] is False
        assert result["masked_text"] == ""

    def test_long_text_performance(self):
        """긴 텍스트 처리 (성능 확인)."""
        base = "고객 연락처: 010-1234-5678. 주문이 완료되었습니다. "
        text = base * 100  # ~5000자
        result = mask_pii(text, language="ko")
        assert result["pii_found"] is True
        assert "010-1234-5678" not in result["masked_text"]


class TestE2EMCPToolOutput:
    """TC-PY-SAFE-004c: MCP 도구 JSON 출력 형식 검증."""

    def test_detect_pii_tool_json_output(self):
        """detect_pii 도구 JSON 출력 형식."""
        from safety.server import detect_pii as tool_detect

        result_json = tool_detect("주민번호: 901234-1234567", language="ko")
        result = json.loads(result_json)
        assert "detections" in result
        assert "count" in result
        assert result["count"] >= 1

    def test_mask_pii_tool_json_output(self):
        """mask_pii 도구 JSON 출력 형식."""
        from safety.server import mask_pii as tool_mask

        result_json = tool_mask("전화: 010-1234-5678", language="ko")
        result = json.loads(result_json)
        assert "masked_text" in result
        assert "pii_found" in result
        assert "detections" in result

    def test_validate_output_tool_json_output(self):
        """validate_output 도구 JSON 출력 형식."""
        from safety.server import validate_output as tool_validate

        result_json = tool_validate("안전한 텍스트입니다.", language="ko")
        result = json.loads(result_json)
        assert "safe" in result
        assert "violations" in result
        assert "violation_count" in result
        assert result["safe"] is True
