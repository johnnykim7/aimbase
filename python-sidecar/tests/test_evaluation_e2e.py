"""Evaluation MCP tool JSON output format tests.

Verifies that MCP server tool functions return valid JSON
with expected structure, similar to TC-PY-SAFE-004c pattern.
"""

import json

from evaluation.server import (
    evaluate_rag as tool_evaluate_rag,
    evaluate_llm_output as tool_evaluate_llm,
    compare_prompts as tool_compare,
)


class TestEvaluateRagToolOutput:
    """evaluate_rag 도구 JSON 출력 형식 검증."""

    def test_json_output_structure(self):
        """evaluate_rag 도구 → JSON with faithfulness/relevancy."""
        result_json = tool_evaluate_rag(
            question="Python이란?",
            answer="Python은 프로그래밍 언어입니다",
            contexts=json.dumps(["Python은 고수준 프로그래밍 언어입니다."]),
        )
        result = json.loads(result_json)

        assert "faithfulness" in result
        assert "relevancy" in result
        assert "context_precision" in result
        assert 0.0 <= result["faithfulness"] <= 1.0
        assert 0.0 <= result["relevancy"] <= 1.0

    def test_with_ground_truth(self):
        """ground_truth 포함 시 context_recall 반환."""
        result_json = tool_evaluate_rag(
            question="수도는?",
            answer="서울입니다",
            contexts=json.dumps(["대한민국의 수도는 서울입니다"]),
            ground_truth="서울",
        )
        result = json.loads(result_json)

        assert result["context_recall"] is not None


class TestEvaluateLlmOutputToolOutput:
    """evaluate_llm_output 도구 JSON 출력 형식 검증."""

    def test_json_output_structure(self):
        """evaluate_llm_output 도구 → JSON with scores."""
        result_json = tool_evaluate_llm(
            input_text="테스트 질문",
            output_text="테스트 답변입니다",
            context="테스트 컨텍스트",
        )
        result = json.loads(result_json)

        assert "hallucination_score" in result
        assert "toxicity_score" in result
        assert "bias_score" in result
        assert "details" in result

    def test_selective_metrics(self):
        """특정 메트릭만 요청."""
        result_json = tool_evaluate_llm(
            input_text="입력",
            output_text="출력",
            metrics=json.dumps(["toxicity"]),
        )
        result = json.loads(result_json)

        assert "toxicity_score" in result


class TestComparePromptsToolOutput:
    """compare_prompts 도구 JSON 출력 형식 검증."""

    def test_json_output_structure(self):
        """compare_prompts 도구 → JSON with summary/details."""
        result_json = tool_compare(
            test_cases=json.dumps([
                {"input": "질문1", "expected_output": "답변1"},
            ]),
            prompt_a=json.dumps({"id": "p1", "version": "v1", "template": "{input}"}),
            prompt_b=json.dumps({"id": "p1", "version": "v2", "template": "답변: {input}"}),
        )
        result = json.loads(result_json)

        assert "summary" in result
        assert "details" in result
        assert "prompt_a_avg" in result["summary"]
        assert "prompt_b_avg" in result["summary"]
        assert "improvement_pct" in result["summary"]
