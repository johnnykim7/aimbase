"""Shared test fixtures."""

import pytest


@pytest.fixture
def korean_document():
    """Korean document text for chunking tests."""
    return (
        "인공지능(AI)은 인간의 학습능력, 추론능력, 지각능력을 인공적으로 구현한 컴퓨터 과학의 세부분야를 말한다. "
        "머신러닝은 인공지능의 한 분야로, 컴퓨터가 학습할 수 있도록 하는 알고리즘과 기술을 개발하는 분야이다. "
        "딥러닝은 머신러닝의 한 분야로, 인공신경망을 기반으로 한 학습 방법이다. "
        "자연어 처리(NLP)는 인간의 언어를 컴퓨터가 이해하고 해석할 수 있게 하는 기술이다. "
        "컴퓨터 비전은 디지털 이미지나 비디오에서 의미 있는 정보를 추출하는 분야이다. "
        "강화학습은 에이전트가 환경과 상호작용하며 보상을 최대화하는 행동을 학습하는 방법이다. "
        "전이학습은 하나의 작업에서 학습한 지식을 다른 관련 작업에 적용하는 기법이다. "
        "생성형 AI는 텍스트, 이미지, 음악 등 새로운 콘텐츠를 생성할 수 있는 인공지능 시스템이다. "
        "대규모 언어 모델(LLM)은 방대한 텍스트 데이터로 학습된 자연어 처리 모델로, GPT와 Claude가 대표적이다. "
        "RAG(Retrieval-Augmented Generation)는 외부 지식을 검색하여 LLM의 응답 품질을 향상시키는 기법이다."
    )


@pytest.fixture
def sample_documents():
    """Sample documents for search/rerank tests."""
    return [
        {"content": "반품 정책은 구매 후 30일 이내에만 가능합니다.", "metadata": {"doc_id": "1"}},
        {"content": "교환은 동일 상품으로만 가능하며, 배송비는 고객 부담입니다.", "metadata": {"doc_id": "2"}},
        {"content": "환불은 원래 결제 수단으로 처리되며, 영업일 기준 3-5일 소요됩니다.", "metadata": {"doc_id": "3"}},
        {"content": "불량 상품의 경우 무료 반품이 가능합니다.", "metadata": {"doc_id": "4"}},
        {"content": "온라인 주문 취소는 발송 전까지만 가능합니다.", "metadata": {"doc_id": "5"}},
        {"content": "회원 가입 시 포인트 적립 혜택이 제공됩니다.", "metadata": {"doc_id": "6"}},
        {"content": "배송은 주문 후 1-3일 이내에 완료됩니다.", "metadata": {"doc_id": "7"}},
        {"content": "고객 서비스 센터는 평일 9시-18시에 운영됩니다.", "metadata": {"doc_id": "8"}},
        {"content": "VIP 회원은 무료 반품 서비스를 이용할 수 있습니다.", "metadata": {"doc_id": "9"}},
        {"content": "해외 배송은 추가 요금이 발생할 수 있습니다.", "metadata": {"doc_id": "10"}},
    ]
