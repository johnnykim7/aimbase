"""TC-PY-SAFE-001: Korean PII recognizer unit tests.

Verifies that custom recognizers correctly detect:
- 주민등록번호 (KR_RRN)
- 휴대폰번호 (KR_PHONE_NUMBER)
- 계좌번호 (KR_BANK_ACCOUNT)
"""

import pytest

from safety.recognizers.kr_rrn_recognizer import KrRrnRecognizer
from safety.recognizers.kr_phone_recognizer import KrPhoneRecognizer
from safety.recognizers.kr_bank_account_recognizer import KrBankAccountRecognizer


class TestKrRrnRecognizer:
    """TC-PY-SAFE-001a: 주민등록번호 인식 테스트."""

    @pytest.fixture
    def recognizer(self):
        return KrRrnRecognizer()

    def test_detect_rrn_standard(self, recognizer):
        """하이픈 포함 주민번호 탐지."""
        text = "주민등록번호는 901234-1234567 입니다."
        results = recognizer.analyze(text, entities=["KR_RRN"])
        assert len(results) >= 1
        assert any(r.entity_type == "KR_RRN" for r in results)

    def test_detect_rrn_male_codes(self, recognizer):
        """성별 코드 1~4 모두 탐지."""
        for code in ["1", "2", "3", "4"]:
            text = f"번호: 880101-{code}234567"
            results = recognizer.analyze(text, entities=["KR_RRN"])
            assert len(results) >= 1, f"Failed to detect RRN with gender code {code}"

    def test_no_false_positive_on_random_digits(self, recognizer):
        """일반 숫자열은 탐지하지 않아야 함."""
        text = "주문번호 123456-789012 확인"
        results = recognizer.analyze(text, entities=["KR_RRN"])
        # 뒷자리가 5~9로 시작하면 매칭 안됨
        rrn_results = [r for r in results if r.entity_type == "KR_RRN"]
        assert len(rrn_results) == 0


class TestKrPhoneRecognizer:
    """TC-PY-SAFE-001b: 휴대폰번호 인식 테스트."""

    @pytest.fixture
    def recognizer(self):
        return KrPhoneRecognizer()

    def test_detect_mobile_with_hyphen(self, recognizer):
        """하이픈 포함 휴대폰번호."""
        text = "연락처: 010-1234-5678"
        results = recognizer.analyze(text, entities=["KR_PHONE_NUMBER"])
        assert len(results) >= 1

    def test_detect_mobile_without_hyphen(self, recognizer):
        """하이픈 없는 휴대폰번호."""
        text = "전화번호 01012345678 입니다"
        results = recognizer.analyze(text, entities=["KR_PHONE_NUMBER"])
        assert len(results) >= 1

    def test_detect_mobile_with_spaces(self, recognizer):
        """공백 구분 휴대폰번호."""
        text = "HP: 010 1234 5678"
        results = recognizer.analyze(text, entities=["KR_PHONE_NUMBER"])
        assert len(results) >= 1

    def test_detect_landline(self, recognizer):
        """유선 전화번호(서울)."""
        text = "사무실 02-123-4567"
        results = recognizer.analyze(text, entities=["KR_PHONE_NUMBER"])
        assert len(results) >= 1


class TestKrBankAccountRecognizer:
    """TC-PY-SAFE-001c: 계좌번호 인식 테스트."""

    @pytest.fixture
    def recognizer(self):
        return KrBankAccountRecognizer()

    def test_detect_kb_account(self, recognizer):
        """국민은행 형식 계좌번호."""
        text = "국민은행 계좌번호: 123-45-6789-012"
        results = recognizer.analyze(text, entities=["KR_BANK_ACCOUNT"])
        assert len(results) >= 1

    def test_detect_shinhan_account(self, recognizer):
        """신한은행 형식 계좌번호."""
        text = "입금 계좌: 110-123-456789"
        results = recognizer.analyze(text, entities=["KR_BANK_ACCOUNT"])
        assert len(results) >= 1

    def test_context_boost(self, recognizer):
        """컨텍스트 키워드 있을 때 점수 상향."""
        text_with_context = "입금 계좌번호 123-456-789012"
        text_without_context = "코드는 123-456-789012"
        results_with = recognizer.analyze(text_with_context, entities=["KR_BANK_ACCOUNT"])
        results_without = recognizer.analyze(text_without_context, entities=["KR_BANK_ACCOUNT"])

        if results_with and results_without:
            assert results_with[0].score >= results_without[0].score
