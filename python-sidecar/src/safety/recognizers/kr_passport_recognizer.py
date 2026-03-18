"""Korean passport number (여권번호) recognizer.

Format: [A-Z]{1,2}[0-9]{7,8}
- First letter indicates passport type: M(일반), S(관용), R(거주여권), G(관용/구), D(외교관)
- Followed by 7-8 digits

Examples: M12345678, S1234567, R12345678
"""

from presidio_analyzer import Pattern, PatternRecognizer


class KrPassportRecognizer(PatternRecognizer):
    """한국 여권번호 인식기."""

    # Valid first letters for Korean passports
    VALID_FIRST_LETTERS = {"M", "S", "R", "G", "D"}

    PATTERNS = [
        Pattern(
            "KR_PASSPORT",
            r"\b[A-Z]{1,2}\d{7,8}\b",
            0.4,
        ),
    ]

    CONTEXT = [
        "여권번호", "여권", "passport", "passport number",
        "travel document", "PASSPORT NO",
    ]

    def __init__(self):
        super().__init__(
            supported_entity="KR_PASSPORT",
            patterns=self.PATTERNS,
            context=self.CONTEXT,
            supported_language="en",
        )

    def validate_result(self, pattern_text: str) -> bool:
        """Validate that the first letter is a valid Korean passport type code."""
        text = pattern_text.strip()
        if not text:
            return False
        first_letter = text[0].upper()
        return first_letter in self.VALID_FIRST_LETTERS
