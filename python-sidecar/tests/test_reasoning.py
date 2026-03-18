"""Tests for PY-011: Advanced Reasoning Chain."""

import pytest


class TestReasoningReflection:
    """reflection 전략 테스트."""

    def test_reflection_produces_steps(self):
        from agent.reasoning import run_reasoning_chain

        result = run_reasoning_chain("API 설계 리뷰", "reflection", max_steps=5)

        assert result["strategy_used"] == "reflection"
        assert result["total_steps"] >= 2
        assert len(result["steps"]) >= 2
        assert "result" in result

    def test_reflection_step_structure(self):
        from agent.reasoning import run_reasoning_chain

        result = run_reasoning_chain("코드 리뷰", "reflection", max_steps=5)

        for step in result["steps"]:
            assert "thought" in step
            assert "action" in step
            assert "observation" in step

    def test_reflection_includes_critique(self):
        from agent.reasoning import run_reasoning_chain

        result = run_reasoning_chain("성능 최적화", "reflection", max_steps=5)
        actions = [s["action"] for s in result["steps"]]

        assert "self_critique" in actions
        assert "improve_response" in actions


class TestReasoningPlanAndExecute:
    """plan_and_execute 전략 테스트."""

    def test_creates_plan_and_executes(self):
        from agent.reasoning import run_reasoning_chain

        result = run_reasoning_chain("시스템 아키텍처 설계", "plan_and_execute", max_steps=10)

        assert result["strategy_used"] == "plan_and_execute"
        actions = [s["action"] for s in result["steps"]]
        assert "create_plan" in actions

    def test_synthesizes_results(self):
        from agent.reasoning import run_reasoning_chain

        result = run_reasoning_chain("배포 파이프라인 구축", "plan_and_execute", max_steps=10)
        actions = [s["action"] for s in result["steps"]]

        assert "synthesize" in actions


class TestReasoningReact:
    """react 전략 테스트."""

    def test_react_iterative_reasoning(self):
        from agent.reasoning import run_reasoning_chain

        result = run_reasoning_chain("버그 분석", "react", max_steps=5)

        assert result["strategy_used"] == "react"
        assert result["total_steps"] >= 2

    def test_react_starts_with_analysis(self):
        from agent.reasoning import run_reasoning_chain

        result = run_reasoning_chain("데이터 모델 설계", "react", max_steps=5)

        assert result["steps"][0]["action"] == "analyze_task"


class TestReasoningLLMCallback:
    """PY-022: LLM 콜백 연동 테스트."""

    def test_call_llm_returns_empty_without_config(self):
        from agent.reasoning import _call_llm

        result = _call_llm("test prompt", None)
        assert result == ""

    def test_call_llm_returns_empty_without_callback_url(self):
        from agent.reasoning import _call_llm

        result = _call_llm("test prompt", {"model": "gpt-4"})
        assert result == ""

    def test_call_llm_success(self):
        import json
        from unittest.mock import MagicMock, patch

        from agent.reasoning import _call_llm

        mock_response = MagicMock()
        mock_response.read.return_value = json.dumps({"response": "LLM 응답"}).encode("utf-8")
        mock_response.__enter__ = lambda s: s
        mock_response.__exit__ = MagicMock(return_value=False)

        with patch("urllib.request.urlopen", return_value=mock_response) as mock_urlopen:
            result = _call_llm(
                "test prompt",
                {"callback_url": "http://localhost:8080/llm", "model": "gpt-4"},
            )

        assert result == "LLM 응답"
        mock_urlopen.assert_called_once()

    def test_call_llm_connection_error_fallback(self):
        import urllib.error
        from unittest.mock import patch

        from agent.reasoning import _call_llm

        with patch(
            "urllib.request.urlopen",
            side_effect=urllib.error.URLError("connection refused"),
        ):
            result = _call_llm(
                "test",
                {"callback_url": "http://localhost:9999/llm"},
            )

        assert result == ""

    def test_call_llm_bad_json_fallback(self):
        from unittest.mock import MagicMock, patch

        from agent.reasoning import _call_llm

        mock_response = MagicMock()
        mock_response.read.return_value = b"not json"
        mock_response.__enter__ = lambda s: s
        mock_response.__exit__ = MagicMock(return_value=False)

        with patch("urllib.request.urlopen", return_value=mock_response):
            result = _call_llm(
                "test",
                {"callback_url": "http://localhost:8080/llm"},
            )

        assert result == ""

    def test_reflection_uses_llm_when_configured(self):
        """llm_config 있으면 LLM 콜백 사용."""
        import json
        from unittest.mock import MagicMock, patch

        from agent.reasoning import run_reasoning_chain

        mock_response = MagicMock()
        mock_response.read.return_value = json.dumps({"response": "LLM이 생성한 응답"}).encode("utf-8")
        mock_response.__enter__ = lambda s: s
        mock_response.__exit__ = MagicMock(return_value=False)

        with patch("urllib.request.urlopen", return_value=mock_response):
            result = run_reasoning_chain(
                "테스트 태스크",
                "reflection",
                max_steps=3,
                llm_config={"callback_url": "http://localhost:8080/llm", "model": "test"},
            )

        assert result["total_steps"] >= 2
        # At least one step should contain LLM response
        observations = [s["observation"] for s in result["steps"]]
        assert any("LLM이 생성한 응답" in obs for obs in observations)

    def test_strategies_work_without_llm_config(self):
        """llm_config 없으면 기존 heuristic 동작."""
        from agent.reasoning import run_reasoning_chain

        for strategy in ["reflection", "plan_and_execute", "react"]:
            result = run_reasoning_chain("테스트", strategy, max_steps=3)
            assert result["total_steps"] >= 1
            assert result["strategy_used"] == strategy


class TestReasoningEdgeCases:
    """엣지 케이스 테스트."""

    def test_invalid_strategy_raises_error(self):
        from agent.reasoning import run_reasoning_chain

        with pytest.raises(ValueError, match="Unknown strategy"):
            run_reasoning_chain("test", "invalid")

    def test_max_steps_respected(self):
        from agent.reasoning import run_reasoning_chain

        result = run_reasoning_chain("대규모 태스크", "plan_and_execute", max_steps=2)

        assert result["total_steps"] <= 2

    def test_execution_time_tracked(self):
        from agent.reasoning import run_reasoning_chain

        result = run_reasoning_chain("간단한 태스크", "react", max_steps=3)

        assert "execution_time_ms" in result
        assert result["execution_time_ms"] >= 0
