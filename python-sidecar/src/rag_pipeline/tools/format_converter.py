"""CR-019: Format Converter Tool.

문서 포맷 간 변환 MCP Tool.
LibreOffice headless를 활용하여 DOCX↔PDF↔PPTX↔HTML 변환.
"""

import base64
import logging
import os
import tempfile
from typing import Any

logger = logging.getLogger(__name__)


def convert_format(
    file_base64: str,
    input_format: str,
    output_format: str,
) -> dict[str, Any]:
    """파일 포맷 변환.

    Args:
        file_base64: base64 인코딩된 원본 파일
        input_format: 입력 포맷 (docx, pptx, pdf, html, xlsx 등)
        output_format: 출력 포맷 (pdf, docx, pptx, html 등)

    Returns:
        {file_base64, filename, input_format, output_format, size_bytes, success}
    """
    if input_format == output_format:
        return {"success": False, "error": "Input and output formats are the same"}

    try:
        file_bytes = base64.b64decode(file_base64)
    except Exception as e:
        return {"success": False, "error": f"Invalid base64: {e}"}

    lo_path = _find_libreoffice()
    if not lo_path:
        return {"success": False, "error": "LibreOffice not found. Required for format conversion."}

    try:
        with tempfile.TemporaryDirectory() as tmpdir:
            input_path = os.path.join(tmpdir, f"input.{input_format}")
            with open(input_path, "wb") as f:
                f.write(file_bytes)

            converted_path = _convert_with_libreoffice(input_path, output_format, tmpdir, lo_path)

            with open(converted_path, "rb") as f:
                result_bytes = f.read()

            result_base64 = base64.b64encode(result_bytes).decode("utf-8")
            filename = f"converted.{output_format}"

            logger.info("Format converted: %s → %s (%d bytes)", input_format, output_format, len(result_bytes))

            return {
                "file_base64": result_base64,
                "filename": filename,
                "input_format": input_format,
                "output_format": output_format,
                "size_bytes": len(result_bytes),
                "success": True,
            }

    except Exception as e:
        logger.error("Format conversion failed: %s", e)
        return {"success": False, "error": str(e)}


def _find_libreoffice() -> str:
    """LibreOffice 실행 파일 경로 탐색."""
    env_path = os.getenv("LIBREOFFICE_PATH", "")
    if env_path and os.path.exists(env_path):
        return env_path

    candidates = [
        "/Applications/LibreOffice.app/Contents/MacOS/soffice",
        "/usr/bin/libreoffice",
        "/usr/bin/soffice",
        "/opt/homebrew/bin/soffice",
    ]
    for c in candidates:
        if os.path.exists(c):
            return c
    return ""


def _convert_with_libreoffice(input_path: str, output_format: str, output_dir: str, lo_path: str) -> str:
    """LibreOffice headless로 포맷 변환."""
    import subprocess

    cmd = [lo_path, "--headless", "--convert-to", output_format, "--outdir", output_dir, input_path]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=120)

    if result.returncode != 0:
        raise RuntimeError(f"LibreOffice conversion failed: {result.stderr}")

    basename = os.path.splitext(os.path.basename(input_path))[0]
    converted_path = os.path.join(output_dir, f"{basename}.{output_format}")
    if os.path.exists(converted_path):
        return converted_path

    for f in os.listdir(output_dir):
        if f.endswith(f".{output_format}"):
            return os.path.join(output_dir, f)

    raise RuntimeError(f"Converted file not found in {output_dir}")
