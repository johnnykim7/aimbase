"""Document Parser — parse various file formats using unstructured.

Supports: PDF, DOCX, PPTX, XLSX, CSV, HTML, TXT, Markdown.
Input: base64-encoded file content + file_type hint.
Output: extracted text + metadata (pages, title, etc.).
"""

import base64
import logging
import tempfile
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

# file_type -> (unstructured partition function name, file extension)
_TYPE_MAP: dict[str, tuple[str, str]] = {
    "pdf": ("partition_pdf", ".pdf"),
    "docx": ("partition_docx", ".docx"),
    "doc": ("partition_docx", ".doc"),
    "pptx": ("partition_pptx", ".pptx"),
    "xlsx": ("partition_xlsx", ".xlsx"),
    "csv": ("partition_csv", ".csv"),
    "html": ("partition_html", ".html"),
    "txt": ("partition_text", ".txt"),
    "md": ("partition_text", ".md"),
    "markdown": ("partition_text", ".md"),
}


def parse_document(
    file_content: str,
    file_type: str = "",
) -> dict[str, Any]:
    """Parse a base64-encoded document file into plain text + metadata.

    Args:
        file_content: Base64-encoded file bytes.
        file_type: File type hint (e.g. "pdf", "docx"). If empty, auto-detected.

    Returns:
        {"text": str, "metadata": {"pages": int, "elements": int, "file_type": str, ...}}
    """
    file_type = file_type.lower().strip().lstrip(".")

    try:
        raw_bytes = base64.b64decode(file_content)
    except Exception as exc:
        return {
            "text": "",
            "metadata": {"error": f"Base64 decoding failed: {exc}"},
        }

    # Determine partition function
    if file_type and file_type in _TYPE_MAP:
        partition_fn_name, ext = _TYPE_MAP[file_type]
    elif file_type:
        # Unknown type — try auto partition
        partition_fn_name = "partition"
        ext = f".{file_type}" if file_type else ".bin"
    else:
        partition_fn_name = "partition"
        ext = ".bin"

    # Write to temp file and partition
    try:
        with tempfile.NamedTemporaryFile(suffix=ext, delete=False) as tmp:
            tmp.write(raw_bytes)
            tmp_path = tmp.name

        elements = _partition(partition_fn_name, tmp_path)

        # Extract text
        texts: list[str] = []
        pages: set[int] = set()
        element_types: dict[str, int] = {}

        for el in elements:
            text = str(el).strip()
            if text:
                texts.append(text)

            # Collect metadata
            el_type = type(el).__name__
            element_types[el_type] = element_types.get(el_type, 0) + 1

            if hasattr(el, "metadata"):
                page = getattr(el.metadata, "page_number", None)
                if page is not None:
                    pages.add(page)

        full_text = "\n\n".join(texts)

        # Extract document-level metadata
        doc_metadata: dict[str, Any] = {
            "file_type": file_type or "auto",
            "elements": len(elements),
            "element_types": element_types,
            "text_length": len(full_text),
        }

        if pages:
            doc_metadata["pages"] = max(pages)

        # Try to extract title from first Title element
        for el in elements:
            if type(el).__name__ == "Title":
                doc_metadata["title"] = str(el).strip()
                break

        return {"text": full_text, "metadata": doc_metadata}

    except Exception as exc:
        logger.exception("Document parsing failed for type=%s", file_type)
        return {
            "text": "",
            "metadata": {"error": f"Parsing failed: {exc}", "file_type": file_type},
        }
    finally:
        # Clean up temp file
        try:
            Path(tmp_path).unlink(missing_ok=True)
        except Exception:
            pass


def _partition(fn_name: str, file_path: str) -> list:
    """Dynamically import and call the appropriate unstructured partition function."""
    if fn_name == "partition":
        from unstructured.partition.auto import partition
        return partition(filename=file_path)
    elif fn_name == "partition_pdf":
        from unstructured.partition.pdf import partition_pdf
        return partition_pdf(filename=file_path)
    elif fn_name == "partition_docx":
        from unstructured.partition.docx import partition_docx
        return partition_docx(filename=file_path)
    elif fn_name == "partition_pptx":
        from unstructured.partition.pptx import partition_pptx
        return partition_pptx(filename=file_path)
    elif fn_name == "partition_xlsx":
        from unstructured.partition.xlsx import partition_xlsx
        return partition_xlsx(filename=file_path)
    elif fn_name == "partition_csv":
        from unstructured.partition.csv import partition_csv
        return partition_csv(filename=file_path)
    elif fn_name == "partition_html":
        from unstructured.partition.html import partition_html
        return partition_html(filename=file_path)
    elif fn_name == "partition_text":
        from unstructured.partition.text import partition_text
        return partition_text(filename=file_path)
    else:
        from unstructured.partition.auto import partition
        return partition(filename=file_path)
