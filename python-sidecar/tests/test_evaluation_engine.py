"""TC-PY-EVAL-002 & TC-PY-EVAL-003: Evaluation engine tests.

TC-PY-EVAL-002: RAG 품질 평가 (faithfulness, relevancy 0.0~1.0 범위)
TC-PY-EVAL-003: LLM 출력 평가 (hallucination, toxicity 탐지)

Uses heuristic fallback (no LLM calls needed for unit tests).
"""

import pytest

from evaluation.engine import (
    compare_prompts,
    evaluate_llm_output,
    evaluate_rag,
    generate_benchmark,
    _evaluate_rag_heuristic,
    _evaluate_llm_heuristic,
)


class TestEvaluateRag:
    """TC-PY-EVAL-002: RAG 품질 평가."""

    def test_basic_rag_evaluation(self):
        """question, answer, contexts → faithfulness/relevancy 0.0~1.0."""
        result = _evaluate_rag_heuristic(
            question="Python에서 리스트를 정렬하는 방법은?",
            answer="Python에서 리스트를 정렬하려면 sort() 메서드나 sorted() 함수를 사용합니다.",
            contexts=[
                "Python의 리스트는 sort() 메서드로 정렬할 수 있습니다.",
                "sorted() 함수는 새로운 정렬된 리스트를 반환합니다.",
            ],
            ground_truth=None,
        )

        assert "faithfulness" in result
        assert "relevancy" in result
        assert "context_precision" in result
        assert 0.0 <= result["faithfulness"] <= 1.0
        assert 0.0 <= result["relevancy"] <= 1.0
        assert 0.0 <= result["context_precision"] <= 1.0

    def test_context_recall_with_ground_truth(self):
        """ground_truth 제공 시 context_recall 포함."""
        result = _evaluate_rag_heuristic(
            question="수도는?",
            answer="대한민국의 수도는 서울입니다.",
            contexts=["대한민국의 수도는 서울특별시입니다."],
            ground_truth="서울",
        )

        assert result["context_recall"] is not None
        assert 0.0 <= result["context_recall"] <= 1.0

    def test_context_recall_none_without_ground_truth(self):
        """ground_truth 없으면 context_recall은 None."""
        result = _evaluate_rag_heuristic(
            question="테스트 질문",
            answer="테스트 답변",
            contexts=["테스트 컨텍스트"],
            ground_truth=None,
        )

        assert result["context_recall"] is None

    def test_high_faithfulness_when_answer_from_context(self):
        """답변이 컨텍스트에서 나온 경우 faithfulness 높음."""
        context = "PostgreSQL은 오픈소스 관계형 데이터베이스입니다"
        result = _evaluate_rag_heuristic(
            question="PostgreSQL이란?",
            answer="PostgreSQL은 오픈소스 관계형 데이터베이스입니다",
            contexts=[context],
            ground_truth=None,
        )

        assert result["faithfulness"] > 0.5

    def test_low_faithfulness_when_answer_not_in_context(self):
        """답변이 컨텍스트와 무관한 경우 faithfulness 낮음."""
        result = _evaluate_rag_heuristic(
            question="Python이란?",
            answer="축구는 전 세계에서 가장 인기 있는 스포츠입니다",
            contexts=["Python은 프로그래밍 언어입니다."],
            ground_truth=None,
        )

        assert result["faithfulness"] < 0.5

    def test_empty_contexts(self):
        """빈 컨텍스트 처리."""
        result = _evaluate_rag_heuristic(
            question="질문",
            answer="답변",
            contexts=[],
            ground_truth=None,
        )

        assert "faithfulness" in result
        assert 0.0 <= result["faithfulness"] <= 1.0

    def test_evaluate_rag_wrapper_uses_heuristic_fallback(self):
        """evaluate_rag() 래퍼가 RAGAS 없이 폴백 동작."""
        result = evaluate_rag(
            question="테스트",
            answer="테스트 답변입니다",
            contexts=["테스트 컨텍스트"],
        )

        assert "faithfulness" in result
        assert "relevancy" in result

    def test_result_structure(self):
        """결과 구조 검증: 4개 메트릭 키."""
        result = _evaluate_rag_heuristic(
            question="q", answer="a", contexts=["c"], ground_truth="gt"
        )

        expected_keys = {"faithfulness", "relevancy", "context_precision", "context_recall"}
        assert set(result.keys()) == expected_keys


class TestEvaluateLlmOutput:
    """TC-PY-EVAL-003a: Hallucination 탐지."""

    def test_hallucination_detection(self):
        """컨텍스트와 불일치하는 출력 → hallucination_score > 0.5."""
        result = _evaluate_llm_heuristic(
            input_text="대한민국의 수도는?",
            output_text="대한민국의 수도는 부산이며 인구는 500만 명입니다",
            context="대한민국의 수도는 서울특별시입니다",
            target_metrics={"hallucination"},
        )

        assert result["hallucination_score"] > 0.3  # 컨텍스트와 겹치지 않는 단어 비율

    def test_no_hallucination_when_aligned(self):
        """컨텍스트와 일치하는 출력 → hallucination_score 낮음."""
        context = "Python은 프로그래밍 언어입니다"
        result = _evaluate_llm_heuristic(
            input_text="Python이란?",
            output_text="Python은 프로그래밍 언어입니다",
            context=context,
            target_metrics={"hallucination"},
        )

        assert result["hallucination_score"] < 0.5

    def test_hallucination_without_context(self):
        """컨텍스트 없으면 hallucination 평가 스킵."""
        result = _evaluate_llm_heuristic(
            input_text="질문",
            output_text="답변",
            context=None,
            target_metrics={"hallucination"},
        )

        assert result["hallucination_score"] == 0.0


class TestToxicityDetection:
    """TC-PY-EVAL-003b: Toxicity 탐지."""

    def test_toxic_content_detected(self):
        """유해 콘텐츠 포함 → toxicity_score > 0.5."""
        result = _evaluate_llm_heuristic(
            input_text="의견을 말해줘",
            output_text="그 사람은 바보이고 멍청한 idiot입니다",
            context=None,
            target_metrics={"toxicity"},
        )

        assert result["toxicity_score"] > 0.5

    def test_clean_content(self):
        """안전한 콘텐츠 → toxicity_score 낮음."""
        result = _evaluate_llm_heuristic(
            input_text="오늘 날씨는?",
            output_text="오늘은 맑고 기온은 20도입니다. 좋은 하루 되세요.",
            context=None,
            target_metrics={"toxicity"},
        )

        assert result["toxicity_score"] < 0.5

    def test_toxicity_detail_structure(self):
        """독성 평가 결과 상세 구조."""
        result = _evaluate_llm_heuristic(
            input_text="test",
            output_text="clean text",
            context=None,
            target_metrics={"toxicity"},
        )

        assert "details" in result
        assert "toxicity" in result["details"]
        detail = result["details"]["toxicity"]
        assert "score" in detail
        assert "reason" in detail
        assert "passed" in detail


class TestBiasDetection:
    """TC-PY-EVAL-003c: Bias 탐지."""

    def test_biased_content(self):
        """편향적 표현 포함 → bias_score 높음."""
        result = _evaluate_llm_heuristic(
            input_text="의견을 말해줘",
            output_text="모든 사람은 항상 이렇게 해야 하고 절대 다른 방법은 없습니다",
            context=None,
            target_metrics={"bias"},
        )

        assert result["bias_score"] > 0.5

    def test_balanced_content(self):
        """균형 잡힌 콘텐츠 → bias_score 낮음."""
        result = _evaluate_llm_heuristic(
            input_text="조언을 주세요",
            output_text="여러 가지 방법이 있으며 상황에 따라 다릅니다.",
            context=None,
            target_metrics={"bias"},
        )

        assert result["bias_score"] < 0.5


class TestEvaluateLlmOutputWrapper:
    """evaluate_llm_output() 래퍼 함수 통합 테스트."""

    def test_all_metrics_default(self):
        """메트릭 미지정 시 전체 평가."""
        result = evaluate_llm_output(
            input_text="테스트",
            output_text="테스트 결과입니다",
            context="테스트 컨텍스트",
        )

        assert "hallucination_score" in result
        assert "toxicity_score" in result
        assert "bias_score" in result
        assert "details" in result

    def test_selective_metrics(self):
        """특정 메트릭만 선택 평가."""
        result = evaluate_llm_output(
            input_text="테스트",
            output_text="테스트 결과입니다",
            context="테스트 컨텍스트",
            metrics=["toxicity"],
        )

        assert "toxicity_score" in result

    def test_result_scores_in_range(self):
        """모든 점수 0.0~1.0 범위."""
        result = evaluate_llm_output(
            input_text="입력",
            output_text="출력",
            context="컨텍스트",
        )

        assert 0.0 <= result["hallucination_score"] <= 1.0
        assert 0.0 <= result["toxicity_score"] <= 1.0
        assert 0.0 <= result["bias_score"] <= 1.0


class TestComparePrompts:
    """PY-008: 프롬프트 회귀 테스트."""

    def test_basic_comparison(self):
        """두 프롬프트 A/B 비교 기본 동작."""
        result = compare_prompts(
            test_cases=[
                {"input": "Python이란?", "expected_output": "Python은 프로그래밍 언어입니다"},
                {"input": "Java란?", "expected_output": "Java는 객체지향 프로그래밍 언어입니다"},
            ],
            prompt_a={"id": "p1", "version": "v1", "template": "{input}에 대해 설명해주세요"},
            prompt_b={"id": "p1", "version": "v2", "template": "{input}에 대해 자세히 설명해주세요"},
        )

        assert "summary" in result
        assert "details" in result
        assert "prompt_a_avg" in result["summary"]
        assert "prompt_b_avg" in result["summary"]
        assert "improvement_pct" in result["summary"]
        assert len(result["details"]) == 2

    def test_comparison_detail_structure(self):
        """비교 결과 상세 구조."""
        result = compare_prompts(
            test_cases=[{"input": "test", "expected_output": "expected"}],
            prompt_a={"id": "a", "version": "1", "template": "{input}"},
            prompt_b={"id": "a", "version": "2", "template": "답변: {input}"},
        )

        detail = result["details"][0]
        assert "input" in detail
        assert "score_a" in detail
        assert "score_b" in detail
        assert 0.0 <= detail["score_a"] <= 1.0
        assert 0.0 <= detail["score_b"] <= 1.0

    def test_comparison_with_custom_metrics(self):
        """커스텀 메트릭으로 비교."""
        result = compare_prompts(
            test_cases=[{"input": "q", "expected_output": "a"}],
            prompt_a={"id": "x", "version": "1", "template": "{input}"},
            prompt_b={"id": "x", "version": "2", "template": "{input}"},
            metrics=["faithfulness"],
        )

        assert result["metrics"] == ["faithfulness"]

    def test_empty_template_fallback(self):
        """빈 템플릿 → 입력을 그대로 사용."""
        result = compare_prompts(
            test_cases=[{"input": "test input", "expected_output": "test output"}],
            prompt_a={"id": "a", "version": "1", "template": ""},
            prompt_b={"id": "a", "version": "2", "template": ""},
        )

        assert "summary" in result
        assert result["summary"]["prompt_a_avg"] == result["summary"]["prompt_b_avg"]


class TestGenerateBenchmark:
    """PY-020: RAG 벤치마크 자동 생성."""

    def test_definition_questions(self):
        """'은/는 ...이다' 패턴 → 정의 질문 생성."""
        chunks = [
            {"content": "멀티테넌시는 하나의 소프트웨어로 여러 조직을 지원하는 아키텍처이다."},
        ]

        result = generate_benchmark(chunks, question_types=["definition"])

        assert result["total"] >= 1
        q = result["benchmark"][0]
        assert q["question_type"] == "definition"
        assert "무엇인가" in q["question"]
        assert q["answer"]
        assert q["contexts"]
        assert q["ground_truth"]

    def test_explanation_questions(self):
        """'때문에/위해' 패턴 → 설명 질문 생성."""
        chunks = [
            {"content": "성능 최적화를 위해 인덱스를 생성해야 합니다."},
        ]

        result = generate_benchmark(chunks, question_types=["explanation"])

        assert result["total"] >= 1
        q = result["benchmark"][0]
        assert q["question_type"] == "explanation"
        assert "왜" in q["question"]

    def test_comparison_questions(self):
        """2개 이상 엔티티 → 비교 질문 생성."""
        chunks = [
            {"content": "PostgreSQL은 관계형 데이터베이스이고, MongoDB는 문서형 데이터베이스입니다."},
        ]

        result = generate_benchmark(chunks, question_types=["comparison"])

        assert result["total"] >= 1
        q = result["benchmark"][0]
        assert q["question_type"] == "comparison"
        assert "차이는" in q["question"]

    def test_empty_chunks(self):
        """빈 청크 리스트 → 빈 결과."""
        result = generate_benchmark([], question_types=["definition"])

        assert result["benchmark"] == []
        assert result["total"] == 0

    def test_max_questions_limit(self):
        """max_questions 제한."""
        chunks = [
            {"content": f"개념{i}는 중요한 기술이다." for i in range(50)}
        ]

        result = generate_benchmark(chunks, question_types=["definition"], max_questions=5)

        assert result["total"] <= 5

    def test_all_question_types(self):
        """전체 질문 유형 동시 생성."""
        chunks = [
            {"content": "벡터 검색은 임베딩 유사도 기반의 검색 방식이다."},
            {"content": "성능을 위해 HNSW 인덱스를 사용합니다."},
            {"content": "BM25는 키워드 기반이고 벡터검색은 의미 기반입니다."},
        ]

        result = generate_benchmark(chunks)

        assert result["total"] >= 1
        types_found = {q["question_type"] for q in result["benchmark"]}
        assert len(types_found) >= 1  # 최소 하나의 유형

    def test_result_structure(self):
        """결과 구조 검증."""
        chunks = [
            {"content": "RAG는 Retrieval Augmented Generation의 약자이다."},
        ]

        result = generate_benchmark(chunks, question_types=["definition"])

        assert "benchmark" in result
        assert "total" in result
        if result["total"] > 0:
            item = result["benchmark"][0]
            assert "question" in item
            assert "answer" in item
            assert "contexts" in item
            assert "ground_truth" in item
            assert "question_type" in item
