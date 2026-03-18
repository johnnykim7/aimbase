"""Korean driver's license number (운전면허번호) recognizer.

Format: XX-XX-XXXXXX-XX
- First 2 digits: 지역코드 (11~28)
- Next 2 digits: 면허종류
- Next 6 digits: 일련번호
- Last 2 digits: 체크 코드

Example: 11-23-123456-01
"""

from presidio_analyzer import Pattern, PatternRecognizer


class KrDriverLicenseRecognizer(PatternRecognizer):
    """한국 운전면허번호 인식기."""

    PATTERNS = [
        Pattern(
            "KR_DRIVER_LICENSE",
            r"\b\d{2}-\d{2}-\d{6}-\d{2}\b",
            0.7,
        ),
    ]

    CONTEXT = [
        "운전면허", "면허번호", "운전면허번호", "driver license",
        "driving license", "면허증",
    ]

    def __init__(self):
        super().__init__(
            supported_entity="KR_DRIVER_LICENSE",
            patterns=self.PATTERNS,
            context=self.CONTEXT,
            supported_language="en",
        )
