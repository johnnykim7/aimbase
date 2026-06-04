"""Document Parser — parse various file formats using unstructured.

Supports: PDF, DOCX, PPTX, XLSX, CSV, HTML, TXT, Markdown.
Input: base64-encoded file content + file_type hint.
Output: extracted text + metadata (pages, title, etc.).
"""

import base64
import logging
import os
import tempfile
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

# 다운로드 안전 한계
_MAX_DOWNLOAD_BYTES = 100 * 1024 * 1024  # 100MB
# 대용량 PDF(수 MB) 다운로드가 60초를 넘기는 사례 대응 → 150초로 상향.
# BE 측 MCP requestTimeout(180초) 보다 작게 유지해 BE 가 먼저 끊지 않게 정합.
# 환경변수 PARSE_DOWNLOAD_TIMEOUT_SEC 로 조정 가능.
_DOWNLOAD_TIMEOUT_SEC = int(os.getenv("PARSE_DOWNLOAD_TIMEOUT_SEC", "150"))


def _download_bytes(url: str) -> bytes:
    """URL에서 파일 바이트를 직접 받는다 (base64 왕복 없이 파싱에 바로 사용)."""
    import urllib.request

    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (compatible; Aimbase/1.0)"
    })
    with urllib.request.urlopen(req, timeout=_DOWNLOAD_TIMEOUT_SEC) as resp:
        data = resp.read(_MAX_DOWNLOAD_BYTES + 1)
    if len(data) > _MAX_DOWNLOAD_BYTES:
        raise ValueError(f"File exceeds max size {_MAX_DOWNLOAD_BYTES} bytes")
    logger.info("parse_document: downloaded %d bytes from %s", len(data), url)
    return data

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
    file_content: str = "",
    file_type: str = "",
    url: str = "",
) -> dict[str, Any]:
    """Parse a document file into plain text + metadata.

    소스는 둘 중 하나: base64(file_content) 또는 url(다운로드). url이 주어지면
    바이트를 직접 받아 파싱한다(base64 왕복 없음).

    Args:
        file_content: Base64-encoded file bytes. (url 미지정 시 필수)
        file_type: File type hint (e.g. "pdf", "docx"). If empty, auto-detected from url ext.
        url: 다운로드할 파일 URL (http/https). 지정 시 file_content 무시.

    Returns:
        {"text": str, "metadata": {"pages": int, "elements": int, "file_type": str, ...}}
    """
    file_type = file_type.lower().strip().lstrip(".")

    if url and url.strip():
        try:
            raw_bytes = _download_bytes(url.strip())
        except Exception as exc:
            return {
                "text": "",
                "metadata": {"error": f"Download failed: {exc}", "url": url},
            }
        # url 확장자로 file_type 자동 추정 (미지정 시)
        if not file_type:
            from urllib.parse import urlparse
            ext = urlparse(url).path.rsplit(".", 1)
            if len(ext) == 2:
                file_type = ext[1].lower().strip()
    else:
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
