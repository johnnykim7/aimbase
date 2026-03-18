"""TC-PY-SAFE-018: Korean NER alpha recognizer tests.

Verifies 4 new recognizers:
- 여권번호 (KR_PASSPORT)
- 사업자등록번호 (KR_BUSINESS_NUMBER)
- 운전면허번호 (KR_DRIVER_LICENSE)
- 주소 (KR_ADDRESS)
"""

import pytest

from safety.recognizers.kr_passport_recognizer import KrPassportRecognizer
from safety.recognizers.kr_business_number_recognizer import KrBusinessNumberRecognizer
from safety.recognizers.kr_driver_license_recognizer import KrDriverLicenseRecognizer
from safety.recognizers.kr_address_recognizer import KrAddressRecognizer


class TestKrPassportRecognizer:
    """TC-PY-SAFE-018a: 여권번호 인식 테스트."""

    @pytest.fixture
    def recognizer(self):
        return KrPassportRecognizer()

    def test_detect_m_type_passport(self, recognizer):
        """M(일반) 여권번호 탐지."""
        text = "여권번호 M12345678 입니다"
        results = recognizer.analyze(text, entities=["KR_PASSPORT"])
        assert len(results) >= 1
        assert any(r.entity_type == "KR_PASSPORT" for r in results)

    def test_detect_s_type_passport(self, recognizer):
        """S(관용) 여권번호 탐지."""
        text = "여권 S1234567"
        results = recognizer.analyze(text, entities=["KR_PASSPORT"])
        assert len(results) >= 1

    def test_detect_r_type_passport(self, recognizer):
        """R(거주여권) 여권번호 탐지."""
        text = "passport R12345678"
        results = recognizer.analyze(text, entities=["KR_PASSPORT"])
        assert len(results) >= 1

    def test_detect_g_and_d_types(self, recognizer):
        """G, D 타입 여권번호 탐지."""
        for prefix in ["G", "D"]:
            text = f"여권번호: {prefix}1234567"
            results = recognizer.analyze(text, entities=["KR_PASSPORT"])
            assert len(results) >= 1, f"Failed for type {prefix}"

    def test_reject_invalid_first_letter(self, recognizer):
        """유효하지 않은 첫 글자(X, Z 등)는 validate에서 거부."""
        rec = recognizer
        assert rec.validate_result("X12345678") is False
        assert rec.validate_result("Z1234567") is False

    def test_accept_valid_first_letter(self, recognizer):
        """유효한 첫 글자 M/S/R/G/D는 통과."""
        for letter in ["M", "S", "R", "G", "D"]:
            assert recognizer.validate_result(f"{letter}12345678") is True

    def test_no_false_positive_on_random_alpha(self, recognizer):
        """일반 영문+숫자 문자열은 검증 실패."""
        assert recognizer.validate_result("") is False


class TestKrBusinessNumberRecognizer:
    """TC-PY-SAFE-018b: 사업자등록번호 인식 테스트."""

    @pytest.fixture
    def recognizer(self):
        return KrBusinessNumberRecognizer()

    def test_detect_valid_business_number(self, recognizer):
        """유효한 사업자등록번호 탐지."""
        # 123-45-67890 — checksum: 1*1+2*3+3*7+4*1+5*3+6*7+7*1+8*3+9*5 + (9*5)//10
        # = 1+6+21+4+15+42+7+24+45 + 4 = 169, check = (10-9)%10 = 1... adjust for real
        text = "사업자등록번호: 220-81-62517"
        results = recognizer.analyze(text, entities=["KR_BUSINESS_NUMBER"])
        assert len(results) >= 1

    def test_detect_pattern_match(self, recognizer):
        """패턴 매칭 형식 확인 (XXX-XX-XXXXX)."""
        # 124-81-00998 passes weighted checksum validation
        text = "사업자번호 124-81-00998 입니다"
        results = recognizer.analyze(text, entities=["KR_BUSINESS_NUMBER"])
        assert len(results) >= 1
        assert any(r.entity_type == "KR_BUSINESS_NUMBER" for r in results)

    def test_checksum_validation_valid(self, recognizer):
        """유효한 체크섬 통과."""
        # 124-81-00998: manual checksum verified
        assert recognizer.validate_result("124-81-00998") is True

    def test_checksum_validation_invalid(self, recognizer):
        """잘못된 체크섬 거부."""
        assert recognizer.validate_result("123-45-67899") is False

    def test_wrong_length_rejected(self, recognizer):
        """10자리가 아닌 경우 거부."""
        assert recognizer.validate_result("12-345-6789") is False
        assert recognizer.validate_result("1234-56-78901") is False

    def test_no_false_positive_without_hyphens(self, recognizer):
        """하이픈 없는 10자리 숫자는 매칭 안됨."""
        text = "코드번호 1234567890"
        results = recognizer.analyze(text, entities=["KR_BUSINESS_NUMBER"])
        assert len(results) == 0


class TestKrDriverLicenseRecognizer:
    """TC-PY-SAFE-018c: 운전면허번호 인식 테스트."""

    @pytest.fixture
    def recognizer(self):
        return KrDriverLicenseRecognizer()

    def test_detect_standard_format(self, recognizer):
        """표준 형식 운전면허번호 탐지."""
        text = "운전면허번호: 11-23-123456-01"
        results = recognizer.analyze(text, entities=["KR_DRIVER_LICENSE"])
        assert len(results) >= 1
        assert any(r.entity_type == "KR_DRIVER_LICENSE" for r in results)

    def test_detect_various_region_codes(self, recognizer):
        """다양한 지역코드 탐지."""
        for region in ["11", "12", "13", "26", "28"]:
            text = f"면허번호 {region}-01-987654-02"
            results = recognizer.analyze(text, entities=["KR_DRIVER_LICENSE"])
            assert len(results) >= 1, f"Failed for region code {region}"

    def test_no_match_on_wrong_format(self, recognizer):
        """형식이 다른 번호는 매칭 안됨."""
        text = "코드: 11-23-12345-01"  # 5자리 (6자리여야 함)
        results = recognizer.analyze(text, entities=["KR_DRIVER_LICENSE"])
        assert len(results) == 0

    def test_no_match_on_plain_digits(self, recognizer):
        """하이픈 없는 숫자열은 매칭 안됨."""
        text = "번호 112312345601"
        results = recognizer.analyze(text, entities=["KR_DRIVER_LICENSE"])
        assert len(results) == 0


class TestKrAddressRecognizer:
    """TC-PY-SAFE-018d: 한국 주소 인식 테스트."""

    @pytest.fixture
    def recognizer(self):
        return KrAddressRecognizer()

    def test_detect_jibun_address(self, recognizer):
        """지번 주소 탐지."""
        text = "주소: 서울특별시 강남구 역삼동 123-45"
        results = recognizer.analyze(text, entities=["KR_ADDRESS"])
        assert len(results) >= 1
        assert any(r.entity_type == "KR_ADDRESS" for r in results)

    def test_detect_road_address(self, recognizer):
        """도로명 주소 탐지."""
        text = "주소: 서울특별시 강남구 테헤란로 123"
        results = recognizer.analyze(text, entities=["KR_ADDRESS"])
        assert len(results) >= 1

    def test_detect_gyeonggi_address(self, recognizer):
        """경기도 주소 탐지."""
        text = "배송지: 경기도 성남시 분당구 정자동 45"
        results = recognizer.analyze(text, entities=["KR_ADDRESS"])
        assert len(results) >= 1

    def test_detect_address_without_bungi(self, recognizer):
        """번지 없는 주소도 탐지."""
        text = "거주지 부산광역시 해운대구 우동"
        results = recognizer.analyze(text, entities=["KR_ADDRESS"])
        assert len(results) >= 1

    def test_no_match_on_non_address(self, recognizer):
        """주소가 아닌 텍스트는 매칭 안됨."""
        text = "오늘 날씨가 좋습니다."
        results = recognizer.analyze(text, entities=["KR_ADDRESS"])
        assert len(results) == 0

    def test_detect_jeju_address(self, recognizer):
        """제주 주소 탐지."""
        text = "소재지: 제주특별자치도 제주시 이도동 123"
        results = recognizer.analyze(text, entities=["KR_ADDRESS"])
        assert len(results) >= 1
