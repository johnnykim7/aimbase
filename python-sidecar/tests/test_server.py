"""TC-PY-RAG-007: MCP Server integration test.

Verifies that the FastMCP server correctly exposes all RAG tools.
"""

from rag_pipeline.server import mcp


class TestMCPServerTools:
    """TC-PY-RAG-007: MCP server tool registration."""

    def test_all_tools_registered(self):
        """All 5 RAG pipeline tools should be registered."""
        tool_names = {tool.name for tool in mcp._tool_manager.tools.values()}

        expected_tools = {
            "chunk_document",
            "embed_texts",
            "search_hybrid",
            "rerank_results",
            "ingest_document",
        }

        assert expected_tools.issubset(tool_names), (
            f"Missing tools: {expected_tools - tool_names}"
        )

    def test_tool_descriptions_present(self):
        """Each tool should have a description."""
        for tool in mcp._tool_manager.tools.values():
            assert tool.description, f"Tool '{tool.name}' has no description"
