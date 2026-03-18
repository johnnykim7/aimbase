"""Korean bank account number (계좌번호) recognizer.

한국 주요 은행 계좌번호 패턴:
- 국민: XXX-XX-XXXX-XXX (12자리)
- 신한: XXX-XXX-XXXXXX (12자리)
- 우리: XXXX-XXX-XXXXXX (13자리)
- 하나: XXX-XXXXXX-XXXXX (14자리)
- 농협: XXX-XXXX-XXXX-XX (13자리)
- 카카오뱅크: XXXX-XX-XXXXXXX (13자리)

컨텍스트(계좌, 입금 등) 키워드와 함께 사용 시 신뢰도 향상.
"""

from presidio_analyzer import Pattern, PatternRecognizer


class KrBankAccountRecognizer(PatternRecognizer):
    """한국 은행 계좌번호 인식기."""

    PATTERNS = [
        # 하이픈으로 구분된 계좌번호 (10~14자리, 2~4개 구간)
        Pattern(
            "KR_BANK_ACCOUNT_HYPHEN",
            r"\b\d{3,4}\s*[-–]\s*\d{2,6}\s*[-–]\s*\d{4,7}(?:\s*[-–]\s*\d{2,5})?\b",
            0.3,
        ),
    ]

    CONTEXT = [
        "계좌번호", "계좌", "입금", "출금", "이체",
        "bank account", "account number",
        "국민은행", "신한은행", "우리은행", "하나은행",
        "농협", "카카오뱅크", "토스", "케이뱅크",
    ]

    def __init__(self):
        super().__init__(
            supported_entity="KR_BANK_ACCOUNT",
            patterns=self.PATTERNS,
            context=self.CONTEXT,
            supported_language="en",
        )
