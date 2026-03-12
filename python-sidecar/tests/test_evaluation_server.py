"""TC-PY-EVAL-001: Evaluation MCP Server tool registration test.

Verifies that the FastMCP server correctly exposes all 3 evaluation tools.
"""

from evaluation.server import mcp


class TestEvaluationMCPServer:
    """TC-PY-EVAL-001: MCP server tool registration."""

    def test_all_tools_registered(self):
        """All 3 evaluation tools should be registered."""
        tool_names = {tool.name for tool in mcp._tool_manager.tools.values()}

        expected_tools = {
            "evaluate_rag",
            "evaluate_llm_output",
            "compare_prompts",
        }

        assert expected_tools.issubset(tool_names), (
            f"Missing tools: {expected_tools - tool_names}"
        )

    def test_tool_descriptions_present(self):
        """Each tool should have a description."""
        for tool in mcp._tool_manager.tools.values():
            assert tool.description, f"Tool '{tool.name}' has no description"

    def test_tool_count(self):
        """Exactly 3 evaluation tools should be registered."""
        assert len(mcp._tool_manager.tools) == 3
