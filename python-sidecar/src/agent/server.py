"""Aimbase Agent - FastMCP Server.

MCP Server 4: Advanced Reasoning Chains
Tools: run_reasoning_chain
"""

import json
import logging

from fastmcp import FastMCP

from agent.config import settings

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

mcp = FastMCP(
    "Aimbase Agent",
    instructions="Advanced reasoning chain tools: reflection, plan-and-execute, ReAct patterns",
)


@mcp.tool()
def run_reasoning_chain(
    task: str,
    strategy: str = "react",
    max_steps: int = 10,
    llm_config: str = "{}",
) -> str:
    """Execute an advanced reasoning chain on the given task.

    Strategies:
    - reflection: Think → Act → Observe → Reflect → Improve (self-critique loop)
    - plan_and_execute: Create plan → Execute sub-tasks → Synthesize results
    - react: ReAct pattern — interleaved Thought → Action → Observation loop

    Args:
        task: The task description to reason about
        strategy: Reasoning strategy - "reflection", "plan_and_execute", or "react"
        max_steps: Maximum number of reasoning steps (default: 10)
        llm_config: JSON string with optional LLM config {model, connection_id}
    """
    from agent.reasoning import run_reasoning_chain as do_reasoning

    cfg = json.loads(llm_config) if isinstance(llm_config, str) else llm_config
    result = do_reasoning(task, strategy, max_steps, cfg if cfg else None)
    return json.dumps(result, ensure_ascii=False)


def main():
    """Start the Agent MCP server with SSE transport."""
    logger.info(
        "Starting Aimbase Agent MCP Server on %s:%d",
        settings.MCP_HOST,
        settings.MCP_PORT,
    )
    mcp.run(transport="sse", host=settings.MCP_HOST, port=settings.MCP_PORT)


if __name__ == "__main__":
    main()
