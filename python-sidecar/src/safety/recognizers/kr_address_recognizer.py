"""Korean address (한국 주소) recognizer.

Detects Korean addresses containing:
- 시/도 + 구/군/시 + 동/읍/면/리 + 번지(optional)
- 도로명 주소: ~로/길 + 번호

Examples:
- 서울특별시 강남구 역삼동 123-45
- 경기도 성남시 분당구 정자동 45
- 서울시 강남구 테헤란로 123
"""

from presidio_analyzer import Pattern, PatternRecognizer


class KrAddressRecognizer(PatternRecognizer):
    """한국 주소 인식기."""

    PATTERNS = [
        # 지번 주소: 시/도 + (시/구/군)+ + 동/읍/면/리 + 번지
        Pattern(
            "KR_ADDRESS_JIBUN",
            r"(?:서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주)"
            r"(?:특별시|광역시|특별자치시|특별자치도|도|시)?\s*"
            r"(?:\S{1,10}(?:구|군|시)\s*){1,2}"
            r"(?:\S{1,10}(?:동|읍|면|리|가))"
            r"(?:\s*\d{1,5}(?:-\d{1,5})?)?",
            0.6,
        ),
        # 도로명 주소: 시/도 + (구/군/시)+ + ~로/길 + 번호
        Pattern(
            "KR_ADDRESS_ROAD",
            r"(?:서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주)"
            r"(?:특별시|광역시|특별자치시|특별자치도|도|시)?\s*"
            r"(?:\S{1,10}(?:구|군|시)\s*){1,2}"
            r"(?:\S{1,20}(?:로|길))\s*"
            r"\d{1,5}",
            0.6,
        ),
    ]

    CONTEXT = [
        "주소", "거주지", "소재지", "address", "addr",
        "배송지", "배달주소", "우편번호", "자택",
    ]

    def __init__(self):
        super().__init__(
            supported_entity="KR_ADDRESS",
            patterns=self.PATTERNS,
            context=self.CONTEXT,
            supported_language="en",
        )
