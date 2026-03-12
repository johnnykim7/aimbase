"""Korean phone number (휴대폰번호) recognizer.

Formats:
- 010-1234-5678
- 010 1234 5678
- 01012345678
- 02-123-4567 (유선)
- 031-123-4567 (지역번호)
"""

from presidio_analyzer import Pattern, PatternRecognizer


class KrPhoneRecognizer(PatternRecognizer):
    """한국 전화번호 인식기."""

    PATTERNS = [
        # 휴대폰: 010/011/016/017/018/019
        Pattern(
            "KR_MOBILE",
            r"\b01[016789]\s*[-–.]?\s*\d{3,4}\s*[-–.]?\s*\d{4}\b",
            0.7,
        ),
        # 유선 (서울 02, 지역번호 0XX)
        Pattern(
            "KR_LANDLINE",
            r"\b0(?:2|[3-6][1-5])\s*[-–.]?\s*\d{3,4}\s*[-–.]?\s*\d{4}\b",
            0.5,
        ),
    ]

    CONTEXT = [
        "전화번호", "휴대폰", "핸드폰", "연락처", "phone",
        "mobile", "tel", "hp", "셀",
    ]

    def __init__(self):
        super().__init__(
            supported_entity="KR_PHONE_NUMBER",
            patterns=self.PATTERNS,
            context=self.CONTEXT,
            supported_language="ko",
        )
