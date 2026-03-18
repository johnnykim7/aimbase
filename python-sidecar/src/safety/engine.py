"""PII Detection & Masking Engine.

Presidio AnalyzerEngine + AnonymizerEngine에 한국어 커스텀 recognizer를 등록.
"""

import logging
from typing import Any

from presidio_analyzer import AnalyzerEngine, RecognizerRegistry
from presidio_anonymizer import AnonymizerEngine
from presidio_anonymizer.entities import OperatorConfig

from safety.config import settings
from safety.recognizers.kr_address_recognizer import KrAddressRecognizer
from safety.recognizers.kr_bank_account_recognizer import KrBankAccountRecognizer
from safety.recognizers.kr_business_number_recognizer import KrBusinessNumberRecognizer
from safety.recognizers.kr_driver_license_recognizer import KrDriverLicenseRecognizer
from safety.recognizers.kr_passport_recognizer import KrPassportRecognizer
from safety.recognizers.kr_phone_recognizer import KrPhoneRecognizer
from safety.recognizers.kr_rrn_recognizer import KrRrnRecognizer

logger = logging.getLogger(__name__)

# 지원하는 모든 엔티티 타입
ALL_ENTITY_TYPES = [
    # 한국어 커스텀
    "KR_RRN",
    "KR_PHONE_NUMBER",
    "KR_BANK_ACCOUNT",
    "KR_PASSPORT",
    "KR_BUSINESS_NUMBER",
    "KR_DRIVER_LICENSE",
    "KR_ADDRESS",
    # Presidio 내장 (영어)
    "EMAIL_ADDRESS",
    "CREDIT_CARD",
    "PHONE_NUMBER",
    "IP_ADDRESS",
    "URL",
]

# 엔티티별 마스킹 연산자 설정
OPERATOR_MAP = {
    "KR_RRN": OperatorConfig("replace", {"new_value": "******-*******"}),
    "KR_PHONE_NUMBER": OperatorConfig("mask", {"masking_char": "*", "chars_to_mask": 8, "from_end": True}),
    "KR_BANK_ACCOUNT": OperatorConfig("mask", {"masking_char": "*", "chars_to_mask": 100, "from_end": False}),
    "KR_PASSPORT": OperatorConfig("replace", {"new_value": "*********"}),
    "KR_BUSINESS_NUMBER": OperatorConfig("replace", {"new_value": "***-**-*****"}),
    "KR_DRIVER_LICENSE": OperatorConfig("replace", {"new_value": "**-**-******-**"}),
    "KR_ADDRESS": OperatorConfig("replace", {"new_value": "<주소>"}),
    "EMAIL_ADDRESS": OperatorConfig("replace", {"new_value": "***@***.***"}),
    "CREDIT_CARD": OperatorConfig("replace", {"new_value": "****-****-****-****"}),
    "DEFAULT": OperatorConfig("replace", {"new_value": "<PII>"}),
}


def _build_analyzer() -> AnalyzerEngine:
    """Presidio AnalyzerEngine을 한국어 recognizer와 함께 초기화."""
    registry = RecognizerRegistry()
    registry.load_predefined_recognizers()

    # 한국어 커스텀 recognizer 등록
    registry.add_recognizer(KrRrnRecognizer())
    registry.add_recognizer(KrPhoneRecognizer())
    registry.add_recognizer(KrBankAccountRecognizer())
    registry.add_recognizer(KrPassportRecognizer())
    registry.add_recognizer(KrBusinessNumberRecognizer())
    registry.add_recognizer(KrDriverLicenseRecognizer())
    registry.add_recognizer(KrAddressRecognizer())

    # 한국어 recognizer들은 패턴(regex) 기반이므로 spaCy NLP 불필요.
    # 모든 recognizer를 'en' 언어 컨텍스트로 등록하여 기본 영어 NLP 엔진 사용.
    analyzer = AnalyzerEngine(
        registry=registry,
        supported_languages=["en"],
    )

    logger.info(
        "PII AnalyzerEngine initialized with %d recognizers",
        len(registry.recognizers),
    )
    return analyzer


# 싱글턴 인스턴스
_analyzer: AnalyzerEngine | None = None
_anonymizer: AnonymizerEngine | None = None


def get_analyzer() -> AnalyzerEngine:
    global _analyzer
    if _analyzer is None:
        _analyzer = _build_analyzer()
    return _analyzer


def get_anonymizer() -> AnonymizerEngine:
    global _anonymizer
    if _anonymizer is None:
        _anonymizer = AnonymizerEngine()
    return _anonymizer


def detect_pii(
    text: str,
    language: str = "",
    entities: list[str] | None = None,
    score_threshold: float = 0.0,
) -> list[dict[str, Any]]:
    """텍스트에서 PII를 탐지하고 결과 목록을 반환.

    Returns:
        [{"entity_type": "KR_RRN", "start": 10, "end": 24, "score": 0.85, "text": "901234-1234567"}, ...]
    """
    lang = language or settings.DEFAULT_LANGUAGE
    threshold = score_threshold or settings.DEFAULT_SCORE_THRESHOLD
    target_entities = entities or ALL_ENTITY_TYPES

    results = get_analyzer().analyze(
        text=text,
        language=lang,
        entities=target_entities,
        score_threshold=threshold,
    )

    return [
        {
            "entity_type": r.entity_type,
            "start": r.start,
            "end": r.end,
            "score": round(r.score, 4),
            "text": text[r.start : r.end],
        }
        for r in sorted(results, key=lambda x: x.start)
    ]


def mask_pii(
    text: str,
    language: str = "",
    entities: list[str] | None = None,
    score_threshold: float = 0.0,
) -> dict[str, Any]:
    """텍스트에서 PII를 탐지하고 마스킹된 텍스트를 반환.

    Returns:
        {"masked_text": "...", "detections": [...], "pii_found": True/False}
    """
    lang = language or settings.DEFAULT_LANGUAGE
    threshold = score_threshold or settings.DEFAULT_SCORE_THRESHOLD
    target_entities = entities or ALL_ENTITY_TYPES

    analyzer_results = get_analyzer().analyze(
        text=text,
        language=lang,
        entities=target_entities,
        score_threshold=threshold,
    )

    if not analyzer_results:
        return {"masked_text": text, "detections": [], "pii_found": False}

    # 엔티티별 연산자 매핑
    operators = {}
    for r in analyzer_results:
        if r.entity_type in OPERATOR_MAP:
            operators[r.entity_type] = OPERATOR_MAP[r.entity_type]
        else:
            operators[r.entity_type] = OPERATOR_MAP["DEFAULT"]

    anonymized = get_anonymizer().anonymize(
        text=text,
        analyzer_results=analyzer_results,
        operators=operators,
    )

    detections = [
        {
            "entity_type": r.entity_type,
            "start": r.start,
            "end": r.end,
            "score": round(r.score, 4),
        }
        for r in sorted(analyzer_results, key=lambda x: x.start)
    ]

    return {
        "masked_text": anonymized.text,
        "detections": detections,
        "pii_found": True,
    }


def validate_output(
    text: str,
    language: str = "",
    entities: list[str] | None = None,
    score_threshold: float = 0.0,
) -> dict[str, Any]:
    """LLM 출력 텍스트에 PII가 포함되어 있는지 검증.

    Returns:
        {"safe": True/False, "violations": [...], "violation_count": N}
    """
    detections = detect_pii(text, language, entities, score_threshold)
    return {
        "safe": len(detections) == 0,
        "violations": detections,
        "violation_count": len(detections),
    }
