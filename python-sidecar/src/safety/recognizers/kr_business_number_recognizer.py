"""Korean business registration number (사업자등록번호) recognizer.

Format: XXX-XX-XXXXX (10 digits with hyphens)
Validation: weighted checksum with weights [1,3,7,1,3,7,1,3,5].

The check digit is computed as:
  sum = Σ(digit_i × weight_i) for i in 0..8
  sum += (digit_8 × 5) // 10
  check = (10 - (sum % 10)) % 10
"""

from presidio_analyzer import Pattern, PatternRecognizer


class KrBusinessNumberRecognizer(PatternRecognizer):
    """한국 사업자등록번호 인식기."""

    WEIGHTS = [1, 3, 7, 1, 3, 7, 1, 3, 5]

    PATTERNS = [
        Pattern(
            "KR_BUSINESS_NUMBER",
            r"\b\d{3}-\d{2}-\d{5}\b",
            0.6,
        ),
    ]

    CONTEXT = [
        "사업자등록번호", "사업자번호", "사업자", "등록번호",
        "business registration", "business number", "사업자 등록",
        "법인", "개인사업자",
    ]

    def __init__(self):
        super().__init__(
            supported_entity="KR_BUSINESS_NUMBER",
            patterns=self.PATTERNS,
            context=self.CONTEXT,
            supported_language="en",
        )

    def validate_result(self, pattern_text: str) -> bool:
        """가중치 체크섬으로 유효성 검증."""
        digits = "".join(c for c in pattern_text if c.isdigit())
        if len(digits) != 10:
            return False

        total = sum(int(digits[i]) * self.WEIGHTS[i] for i in range(9))
        # 9번째 자릿수에 대한 추가 보정
        total += (int(digits[8]) * 5) // 10
        check = (10 - (total % 10)) % 10
        return check == int(digits[9])
