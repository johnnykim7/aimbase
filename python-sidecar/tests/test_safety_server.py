"""TC-PY-SAFE-003: Safety MCP Server tool registration test.

Verifies that the FastMCP server correctly exposes all 3 safety tools.
"""

from safety.server import mcp


class TestSafetyMCPServer:
    """TC-PY-SAFE-003: MCP server tool registration."""

    def test_all_tools_registered(self):
        """All 3 safety tools should be registered."""
        tool_names = {tool.name for tool in mcp._tool_manager.tools.values()}

        expected_tools = {
            "detect_pii",
            "mask_pii",
            "validate_output",
        }

        assert expected_tools.issubset(tool_names), (
            f"Missing tools: {expected_tools - tool_names}"
        )

    def test_tool_descriptions_present(self):
        """Each tool should have a description."""
        for tool in mcp._tool_manager.tools.values():
            assert tool.description, f"Tool '{tool.name}' has no description"

    def test_tool_count(self):
        """Exactly 3 safety tools should be registered."""
        assert len(mcp._tool_manager.tools) == 3
