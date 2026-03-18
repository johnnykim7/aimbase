"""Aimbase Evaluation - FastMCP Server.

MCP Server 2: RAG Quality / LLM Output Evaluation
Tools: evaluate_rag, evaluate_llm_output, compare_prompts, generate_benchmark,
       detect_embedding_drift
"""

import json
import logging

from fastmcp import FastMCP

from evaluation.config import settings

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

mcp = FastMCP("Aimbase Evaluation")


@mcp.tool()
def evaluate_rag(
    question: str,
    answer: str,
    contexts: str = "[]",
    ground_truth: str = "",
) -> str:
    """Evaluate RAG response quality using RAGAS metrics.

    Measures faithfulness, answer relevancy, context precision, and context recall.
    All scores are in 0.0~1.0 range (higher is better).

    Args:
        question: The user question that was asked
        answer: The RAG-generated answer to evaluate
        contexts: JSON array of context strings used to generate the answer
        ground_truth: Optional ground truth answer for context_recall metric
    """
    from evaluation.engine import evaluate_rag as do_evaluate

    ctx_list = json.loads(contexts) if isinstance(contexts, str) else contexts
    gt = ground_truth if ground_truth.strip() else None

    result = do_evaluate(question, answer, ctx_list, gt)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def evaluate_llm_output(
    input_text: str,
    output_text: str,
    context: str = "",
    metrics: str = "[]",
) -> str:
    """Evaluate LLM output for hallucination, toxicity, and bias.

    Each score is in 0.0~1.0 range. Higher score = more problematic.
    - hallucination_score: How much output deviates from provided context
    - toxicity_score: Presence of harmful/toxic language
    - bias_score: Presence of biased/one-sided statements

    Args:
        input_text: The user input/prompt
        output_text: The LLM-generated output to evaluate
        context: Optional reference context for hallucination detection
        metrics: JSON array of metrics to evaluate (default: all). Options: "hallucination", "toxicity", "bias"
    """
    from evaluation.engine import evaluate_llm_output as do_evaluate

    metric_list = json.loads(metrics) if isinstance(metrics, str) and metrics.strip() not in ("", "[]") else None
    ctx = context if context.strip() else None

    result = do_evaluate(input_text, output_text, ctx, metric_list)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def compare_prompts(
    test_cases: str,
    prompt_a: str,
    prompt_b: str,
    metrics: str = "[]",
) -> str:
    """A/B compare two prompt versions for quality regression testing.

    Runs each test case against both prompts and returns comparative scores.

    Args:
        test_cases: JSON array of {input: str, expected_output: str} objects
        prompt_a: JSON object {id: str, version: str, template: str} for baseline prompt
        prompt_b: JSON object {id: str, version: str, template: str} for new prompt
        metrics: JSON array of metric names to compare (default: ["relevancy", "faithfulness"])
    """
    from evaluation.engine import compare_prompts as do_compare

    cases = json.loads(test_cases) if isinstance(test_cases, str) else test_cases
    pa = json.loads(prompt_a) if isinstance(prompt_a, str) else prompt_a
    pb = json.loads(prompt_b) if isinstance(prompt_b, str) else prompt_b
    metric_list = json.loads(metrics) if isinstance(metrics, str) and metrics.strip() not in ("", "[]") else None

    result = do_compare(cases, pa, pb, metric_list)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def generate_benchmark(
    chunks: str,
    question_types: str = '["definition", "explanation", "comparison"]',
    max_questions: int = 20,
) -> str:
    """Generate Q&A benchmark dataset from document chunks (PY-020).

    Template-based generation (no LLM required):
    - definition: Extracts "X는 ...이다" patterns → "X란 무엇인가?" questions
    - explanation: Extracts "때문에/위해/하면" patterns → "왜 X인가?" questions
    - comparison: Detects 2+ entities → "A와 B의 차이는?" questions

    Args:
        chunks: JSON array of [{content, metadata?}] document chunks
        question_types: JSON array of question types to generate
        max_questions: Maximum number of questions to generate (default: 20)
    """
    from evaluation.engine import generate_benchmark as do_generate

    chunk_list = json.loads(chunks) if isinstance(chunks, str) else chunks
    types = json.loads(question_types) if isinstance(question_types, str) else question_types

    result = do_generate(chunk_list, types, max_questions)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def detect_embedding_drift(
    source_id: str,
    sample_size: int = 100,
    baseline_stats: str = "{}",
) -> str:
    """Detect embedding distribution drift for a knowledge source (PY-021).

    Compares current embedding similarity distribution against a baseline.
    Recommends reindexing if drift_score > 0.3.

    Args:
        source_id: Knowledge source ID to analyze
        sample_size: Number of random embeddings to sample (default: 100)
        baseline_stats: JSON object with {mean, std} from previous measurement
    """
    from evaluation.drift_detector import detect_embedding_drift as do_detect

    baseline = json.loads(baseline_stats) if isinstance(baseline_stats, str) else baseline_stats
    baseline = baseline if baseline else None

    result = do_detect(source_id, sample_size, baseline)
    return json.dumps(result, ensure_ascii=False)


def main():
    """Start the Evaluation MCP server with SSE transport."""
    logger.info(
        "Starting Aimbase Evaluation MCP Server on %s:%d",
        settings.MCP_HOST,
        settings.MCP_PORT,
    )
    mcp.run(transport="sse", host=settings.MCP_HOST, port=settings.MCP_PORT)


if __name__ == "__main__":
    main()
