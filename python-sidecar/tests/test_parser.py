"""TC-PY-RAG-013: Document parser tool tests.

Verifies parse_document for various formats and error handling.
"""

import base64
from unittest.mock import MagicMock, patch

import pytest

from rag_pipeline.tools.parser import parse_document


class TestParseDocumentBase64:
    """TC-PY-RAG-013a: Base64 decoding tests."""

    def test_invalid_base64_returns_error(self):
        """Invalid base64 should return error metadata, not crash."""
        result = parse_document("!!!not-valid-base64!!!", "txt")
        assert result["text"] == ""
        assert "error" in result["metadata"]
        assert "Base64" in result["metadata"]["error"]

    def test_empty_content_returns_error(self):
        """Empty string base64 decode still works (0 bytes)."""
        result = parse_document("", "txt")
        # Empty string is valid base64 (0 bytes) — may fail at parsing stage
        assert "metadata" in result

    def test_padding_variants(self):
        """Base64 with different padding should decode correctly."""
        text = "Hello, World!"
        encoded = base64.b64encode(text.encode()).decode()
        # Should not raise on valid base64
        result = parse_document(encoded, "txt")
        assert "metadata" in result


class TestParseDocumentFormats:
    """TC-PY-RAG-013b: Format-specific parsing tests."""

    def test_txt_parsing(self):
        """Plain text file parsing."""
        content = "This is a test document.\nSecond line here."
        encoded = base64.b64encode(content.encode()).decode()

        with patch("rag_pipeline.tools.parser._partition") as mock_partition:
            mock_el = MagicMock()
            mock_el.__str__ = lambda self: "This is a test document."
            mock_el.metadata = MagicMock()
            mock_el.metadata.page_number = None
            type(mock_el).__name__ = "NarrativeText"

            mock_partition.return_value = [mock_el]
            result = parse_document(encoded, "txt")

        assert result["text"] == "This is a test document."
        assert result["metadata"]["file_type"] == "txt"
        assert result["metadata"]["elements"] == 1

    def test_pdf_file_type_mapping(self):
        """PDF type should use partition_pdf function."""
        encoded = base64.b64encode(b"fake pdf content").decode()

        with patch("rag_pipeline.tools.parser._partition") as mock_partition:
            mock_partition.return_value = []
            result = parse_document(encoded, "pdf")

        mock_partition.assert_called_once()
        args = mock_partition.call_args[0]
        assert args[0] == "partition_pdf"

    def test_docx_file_type_mapping(self):
        """DOCX type should use partition_docx function."""
        encoded = base64.b64encode(b"fake docx content").decode()

        with patch("rag_pipeline.tools.parser._partition") as mock_partition:
            mock_partition.return_value = []
            result = parse_document(encoded, "docx")

        args = mock_partition.call_args[0]
        assert args[0] == "partition_docx"

    def test_csv_file_type_mapping(self):
        """CSV type should use partition_csv function."""
        encoded = base64.b64encode(b"col1,col2\nval1,val2").decode()

        with patch("rag_pipeline.tools.parser._partition") as mock_partition:
            mock_partition.return_value = []
            result = parse_document(encoded, "csv")

        args = mock_partition.call_args[0]
        assert args[0] == "partition_csv"

    def test_markdown_file_type_alias(self):
        """Both 'md' and 'markdown' should use partition_text."""
        for ft in ["md", "markdown"]:
            encoded = base64.b64encode(b"# Title").decode()
            with patch("rag_pipeline.tools.parser._partition") as mock_partition:
                mock_partition.return_value = []
                result = parse_document(encoded, ft)
            args = mock_partition.call_args[0]
            assert args[0] == "partition_text"

    def test_unknown_type_uses_auto_partition(self):
        """Unknown file type should fallback to auto partition."""
        encoded = base64.b64encode(b"some content").decode()

        with patch("rag_pipeline.tools.parser._partition") as mock_partition:
            mock_partition.return_value = []
            result = parse_document(encoded, "xyz")

        args = mock_partition.call_args[0]
        assert args[0] == "partition"

    def test_empty_type_uses_auto_partition(self):
        """No file type hint should fallback to auto partition."""
        encoded = base64.b64encode(b"some content").decode()

        with patch("rag_pipeline.tools.parser._partition") as mock_partition:
            mock_partition.return_value = []
            result = parse_document(encoded, "")

        args = mock_partition.call_args[0]
        assert args[0] == "partition"


class TestParseDocumentMetadata:
    """TC-PY-RAG-013c: Metadata extraction tests."""

    def test_page_count_extraction(self):
        """Page metadata should be extracted from elements."""
        encoded = base64.b64encode(b"content").decode()

        with patch("rag_pipeline.tools.parser._partition") as mock_partition:
            el1 = MagicMock()
            el1.__str__ = lambda self: "Page 1 content"
            el1.metadata = MagicMock()
            el1.metadata.page_number = 1
            type(el1).__name__ = "NarrativeText"

            el2 = MagicMock()
            el2.__str__ = lambda self: "Page 3 content"
            el2.metadata = MagicMock()
            el2.metadata.page_number = 3
            type(el2).__name__ = "NarrativeText"

            mock_partition.return_value = [el1, el2]
            result = parse_document(encoded, "pdf")

        assert result["metadata"]["pages"] == 3

    def test_title_extraction(self):
        """Title element should be captured in metadata."""
        encoded = base64.b64encode(b"content").decode()

        with patch("rag_pipeline.tools.parser._partition") as mock_partition:
            title_el = MagicMock()
            title_el.__str__ = lambda self: "My Document Title"
            title_el.metadata = MagicMock()
            title_el.metadata.page_number = None
            type(title_el).__name__ = "Title"

            mock_partition.return_value = [title_el]
            result = parse_document(encoded, "txt")

        assert result["metadata"].get("title") == "My Document Title"

    def test_parsing_exception_returns_error(self):
        """Partition failure should return error, not raise."""
        encoded = base64.b64encode(b"bad content").decode()

        with patch("rag_pipeline.tools.parser._partition", side_effect=Exception("parse error")):
            result = parse_document(encoded, "pdf")

        assert result["text"] == ""
        assert "error" in result["metadata"]
        assert "parse error" in result["metadata"]["error"]


class TestParseDocumentServerRegistration:
    """TC-PY-RAG-013d: MCP server tool registration test."""

    def test_parse_document_registered_in_server(self):
        """parse_document should be registered as MCP tool."""
        from rag_pipeline.server import mcp

        tool_names = {tool.name for tool in mcp._tool_manager.tools.values()}
        assert "parse_document" in tool_names
