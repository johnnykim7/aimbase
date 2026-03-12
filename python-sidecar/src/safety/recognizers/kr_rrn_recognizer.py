"""Korean Resident Registration Number (주민등록번호) recognizer.

Format: YYMMDD-G######
- YYMMDD: 생년월일 6자리
- G: 성별 코드 (1~4)
- ######: 나머지 6자리

검증: 가중치 체크섬 알고리즘 적용.
"""

from presidio_analyzer import Pattern, PatternRecognizer


class KrRrnRecognizer(PatternRecognizer):
    """주민등록번호 인식기."""

    PATTERNS = [
        Pattern(
            "KR_RRN",
            r"\b(\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01]))\s*[-–]\s*([1-4]\d{6})\b",
            0.85,
        ),
    ]

    CONTEXT = [
        "주민등록번호", "주민번호", "resident registration",
        "주민등록", "rrn", "ssn",
    ]

    def __init__(self):
        super().__init__(
            supported_entity="KR_RRN",
            patterns=self.PATTERNS,
            context=self.CONTEXT,
            supported_language="ko",
        )

    def validate_result(self, pattern_text: str) -> bool:
        """가중치 체크섬으로 유효성 검증."""
        digits = "".join(c for c in pattern_text if c.isdigit())
        if len(digits) != 13:
            return True  # 길이가 맞지 않으면 패턴 매칭 결과 그대로 사용

        weights = [2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5]
        total = sum(int(digits[i]) * weights[i] for i in range(12))
        check = (11 - (total % 11)) % 10
        return check == int(digits[12])
