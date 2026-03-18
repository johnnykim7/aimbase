"""PY-019: Korean Toxic Reference Sentences.

Reference sentences by toxicity category for embedding-based classification.
Used by guardrails to compute cosine similarity against input text.
"""

TOXIC_REFERENCES: dict[str, list[str]] = {
    "sexism": [
        "여자는 집에서 살림이나 해야 한다",
        "남자가 더 유능하다",
        "여자는 감정적이라 리더가 될 수 없다",
        "남자가 일을 더 잘한다",
        "여자는 운전을 못한다",
    ],
    "racism": [
        "특정 인종은 열등하다",
        "외국인 노동자는 범죄자다",
        "다문화 가정은 문제가 많다",
        "특정 민족은 게으르다",
        "이민자들이 일자리를 빼앗는다",
    ],
    "hate_speech": [
        "장애인은 사회에 기여하지 못한다",
        "노인은 쓸모없다",
        "특정 종교는 위험하다",
        "성소수자는 비정상이다",
        "저소득층은 노력을 안 한다",
    ],
    "profanity": [
        "비속어 포함 문장 예시",
        "욕설과 모욕적인 표현",
        "인신공격적 발언",
        "저급한 표현과 비하",
    ],
}

# Default similarity threshold for embedding-based classification
DEFAULT_TOXIC_THRESHOLD = 0.7
