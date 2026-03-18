"""PY-011: Advanced Reasoning Chain Engine.

Implements reflection, plan-and-execute, and ReAct patterns
for multi-step agent reasoning.

In production, LLM calls should be delegated to Spring orchestrator.
This module provides the reasoning framework with heuristic fallbacks.
PY-022: Added _call_llm for HTTP callback integration.
"""

import json
import logging
import time
import urllib.request
import urllib.error
from typing import Any

logger = logging.getLogger(__name__)

_LLM_TIMEOUT_SECONDS = 30


def run_reasoning_chain(
    task: str,
    strategy: str = "react",
    max_steps: int = 10,
    llm_config: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Execute a reasoning chain on the given task.

    Args:
        task: The task description to reason about
        strategy: "reflection", "plan_and_execute", or "react"
        max_steps: Maximum reasoning steps
        llm_config: Optional LLM config for future Spring orchestrator callback

    Returns:
        {result, steps: [{thought, action, observation}], total_steps, strategy_used, execution_time_ms}
    """
    strategy = strategy.lower().strip()
    start_time = time.time()

    if strategy == "reflection":
        result = _reflection_chain(task, max_steps, llm_config)
    elif strategy == "plan_and_execute":
        result = _plan_and_execute_chain(task, max_steps, llm_config)
    elif strategy == "react":
        result = _react_chain(task, max_steps, llm_config)
    else:
        raise ValueError(
            f"Unknown strategy: {strategy}. Use 'reflection', 'plan_and_execute', or 'react'."
        )

    elapsed_ms = int((time.time() - start_time) * 1000)
    result["execution_time_ms"] = elapsed_ms
    result["strategy_used"] = strategy

    return result


def _reflection_chain(
    task: str, max_steps: int, llm_config: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Reflection pattern: Think → Act → Observe → Reflect → Improve.

    Each step includes self-critique and improvement of the previous response.
    """
    steps: list[dict[str, str]] = []

    # Step 1: Initial analysis
    thought = f"Task analysis: '{task}' requires understanding the core question and generating an initial response."
    action = "generate_initial_response"
    observation = _call_llm(
        f"Analyze and respond to the following task:\n{task}", llm_config,
    ) or _heuristic_response(task)
    steps.append({"thought": thought, "action": action, "observation": observation})

    # Step 2: Self-critique
    if len(steps) < max_steps:
        thought = "Reflecting on the initial response: checking for completeness, accuracy, and clarity."
        action = "self_critique"
        critique = _call_llm(
            f"Critique the following response for completeness, accuracy, and clarity:\n{observation}",
            llm_config,
        ) or _heuristic_critique(observation)
        steps.append({"thought": thought, "action": action, "observation": critique})

    # Step 3: Improved response
    if len(steps) < max_steps:
        thought = f"Incorporating feedback to improve: {critique[:100]}..."
        action = "improve_response"
        improved = _call_llm(
            f"Improve the following response based on this critique.\nOriginal: {observation}\nCritique: {critique}",
            llm_config,
        ) or _heuristic_improve(observation, critique)
        steps.append({"thought": thought, "action": action, "observation": improved})

    return {
        "result": steps[-1]["observation"],
        "steps": steps,
        "total_steps": len(steps),
    }


def _plan_and_execute_chain(
    task: str, max_steps: int, llm_config: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Plan-and-Execute pattern: Create plan → Execute each step → Synthesize.

    Decomposes the task into sub-tasks and executes them sequentially.
    """
    steps: list[dict[str, str]] = []

    # Step 1: Plan
    thought = f"Breaking down task '{task}' into sub-tasks."
    action = "create_plan"
    subtasks = _heuristic_plan(task)
    plan_text = "\n".join(f"{i+1}. {s}" for i, s in enumerate(subtasks))
    steps.append({"thought": thought, "action": action, "observation": plan_text})

    # Step 2+: Execute each subtask
    results = []
    for i, subtask in enumerate(subtasks):
        if len(steps) >= max_steps:
            break
        thought = f"Executing subtask {i+1}/{len(subtasks)}: {subtask}"
        action = f"execute_subtask_{i+1}"
        result = _call_llm(
            f"Execute the following subtask:\n{subtask}", llm_config,
        ) or _heuristic_response(subtask)
        results.append(result)
        steps.append({"thought": thought, "action": action, "observation": result})

    # Final: Synthesize
    if len(steps) < max_steps:
        thought = "Synthesizing results from all subtasks into a final answer."
        action = "synthesize"
        synthesis = _call_llm(
            f"Synthesize the following results for task '{task}':\n" + "\n".join(results),
            llm_config,
        ) or _heuristic_synthesize(task, results)
        steps.append({"thought": thought, "action": action, "observation": synthesis})

    return {
        "result": steps[-1]["observation"],
        "steps": steps,
        "total_steps": len(steps),
    }


def _react_chain(
    task: str, max_steps: int, llm_config: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """ReAct pattern: Thought → Action → Observation loop.

    Interleaves reasoning and acting until the task is complete.
    """
    steps: list[dict[str, str]] = []
    context = task

    # Iterative reasoning
    for i in range(min(max_steps, 5)):
        # Think
        thought = _call_llm(
            f"Think about step {i+1} for task: {context}", llm_config,
        ) or _heuristic_think(context, i)

        # Decide action
        if i == 0:
            action = "analyze_task"
        elif i < 3:
            action = f"gather_information_step_{i}"
        else:
            action = "formulate_answer"

        # Observe
        observation = _call_llm(
            f"Execute action '{action}' for task: {context}", llm_config,
        ) or _heuristic_observe(context, action, i)
        steps.append({"thought": thought, "action": action, "observation": observation})

        # Update context
        context = f"{task}\nPrevious step: {observation[:200]}"

        # Check if we have enough information to answer
        if i >= 2 and action == "formulate_answer":
            break

    return {
        "result": steps[-1]["observation"],
        "steps": steps,
        "total_steps": len(steps),
    }


# ── LLM callback (PY-022) ───────────────────────────────────────────


def _call_llm(prompt: str, llm_config: dict[str, Any] | None = None) -> str:
    """Call an external LLM via HTTP callback.

    If llm_config has a callback_url, makes an HTTP POST with the prompt.
    On any failure, returns empty string so callers fall back to heuristic.

    Args:
        prompt: The prompt to send to the LLM
        llm_config: Optional config with {callback_url, model, ...}

    Returns:
        LLM response text, or empty string on failure/no config.
    """
    if not llm_config or not llm_config.get("callback_url"):
        return ""

    callback_url = llm_config["callback_url"]
    model = llm_config.get("model", "")

    try:
        payload = json.dumps({"prompt": prompt, "model": model}).encode("utf-8")
        req = urllib.request.Request(
            callback_url,
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )

        with urllib.request.urlopen(req, timeout=_LLM_TIMEOUT_SECONDS) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            response_text = body.get("response", "")
            if response_text:
                return response_text
            logger.warning("LLM callback returned empty response")
            return ""

    except urllib.error.URLError as e:
        logger.warning("LLM callback connection error: %s", e)
        return ""
    except TimeoutError:
        logger.warning("LLM callback timed out after %ds", _LLM_TIMEOUT_SECONDS)
        return ""
    except (json.JSONDecodeError, KeyError, ValueError) as e:
        logger.warning("LLM callback bad response: %s", e)
        return ""
    except Exception as e:
        logger.warning("LLM callback unexpected error: %s", e)
        return ""


# ── Heuristic helpers (LLM-free) ────────────────────────────────────


def _heuristic_response(task: str) -> str:
    """Generate a heuristic response for a task."""
    return (
        f"[Analysis of '{task[:80]}'] "
        f"This task involves examining the core requirements, "
        f"identifying key components, and providing a structured response. "
        f"Key considerations include context, constraints, and expected outcomes."
    )


def _heuristic_critique(response: str) -> str:
    """Generate a heuristic critique of a response."""
    return (
        f"The response covers basic aspects but could be improved in: "
        f"1) Specificity — more concrete examples needed. "
        f"2) Depth — deeper analysis of root causes. "
        f"3) Actionability — clearer next steps."
    )


def _heuristic_improve(original: str, critique: str) -> str:
    """Generate an improved response based on critique."""
    return (
        f"[Improved] {original} "
        f"Additionally, addressing the identified gaps: "
        f"providing specific examples, deeper analysis of underlying factors, "
        f"and concrete actionable recommendations for next steps."
    )


def _heuristic_plan(task: str) -> list[str]:
    """Decompose a task into subtasks."""
    return [
        f"Understand the context and requirements of: {task[:50]}",
        f"Identify key components and dependencies",
        f"Analyze constraints and potential challenges",
        f"Formulate a comprehensive solution",
    ]


def _heuristic_synthesize(task: str, results: list[str]) -> str:
    """Synthesize multiple results into a final answer."""
    combined = " | ".join(r[:100] for r in results)
    return (
        f"[Synthesis for '{task[:50]}'] "
        f"Based on {len(results)} analysis steps: {combined[:300]}. "
        f"The key findings point to a structured approach addressing all identified components."
    )


def _heuristic_think(context: str, step: int) -> str:
    """Generate a thought for the current step."""
    if step == 0:
        return f"I need to understand the task: {context[:100]}"
    elif step == 1:
        return "Now I should gather relevant information to address the task."
    elif step == 2:
        return "I have enough context to start formulating an answer."
    else:
        return "Let me refine the answer based on what I've gathered."


def _heuristic_observe(context: str, action: str, step: int) -> str:
    """Generate an observation based on the action."""
    if step == 0:
        return (
            f"The task requires analysis of: {context[:80]}. "
            f"Key aspects to address: scope, approach, and expected outcome."
        )
    elif step < 3:
        return (
            f"Additional context gathered. "
            f"The problem space involves multiple interconnected factors "
            f"that need to be considered together."
        )
    else:
        return (
            f"[Final answer for '{context[:50]}'] "
            f"Based on the analysis, the recommended approach involves "
            f"a systematic evaluation of all identified components, "
            f"prioritizing critical factors and addressing dependencies."
        )
