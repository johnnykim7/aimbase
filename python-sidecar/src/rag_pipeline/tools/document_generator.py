"""CR-018: Document Generation MCP Tool.

LLM이 생성한 Python 코드를 실행하여 문서 파일을 생성합니다.
지원 포맷: PPTX, DOCX, PDF, XLSX, CSV, HTML, PNG, JPG, SVG

보안: exec()을 제한된 환경에서 실행하며, 허용된 라이브러리만 import 가능.
"""

import base64
import logging
import os
import tempfile
import traceback
from typing import Any

logger = logging.getLogger(__name__)

# 허용된 모듈 목록 (보안)
ALLOWED_MODULES = {
    "pptx", "pptx.util", "pptx.enum.text", "pptx.enum.chart", "pptx.enum.dml",
    "pptx.chart.data", "pptx.dml.color",
    "docx", "docx.shared", "docx.enum.text", "docx.enum.table", "docx.enum.style",
    "openpyxl", "openpyxl.styles", "openpyxl.chart", "openpyxl.utils",
    "reportlab.lib.pagesizes", "reportlab.lib.units", "reportlab.lib.colors",
    "reportlab.lib.styles", "reportlab.lib.enums",
    "reportlab.platypus", "reportlab.pdfgen.canvas",
    "reportlab.pdfbase", "reportlab.pdfbase.ttfonts", "reportlab.pdfbase.pdfmetrics",
    # Image generation
    "PIL", "PIL.Image", "PIL.ImageDraw", "PIL.ImageFont", "PIL.ImageFilter",
    "PIL.ImageColor", "PIL.ImageEnhance", "PIL.ImageOps",
    # Charting (matplotlib)
    "matplotlib", "matplotlib.pyplot", "matplotlib.figure", "matplotlib.patches",
    "matplotlib.colors", "matplotlib.cm",
    "csv", "io", "json", "datetime", "math", "os.path", "struct", "base64",
}

# 차단 키워드 (보안)
BLOCKED_KEYWORDS = {
    "subprocess", "shutil.rmtree", "os.system", "os.remove", "os.unlink",
    "__import__", "eval(", "compile(", "open('/", 'open("/',
    "socket", "requests", "httpx", "urllib",
}

FORMAT_EXTENSIONS = {
    "pptx": ".pptx",
    "docx": ".docx",
    "pdf": ".pdf",
    "xlsx": ".xlsx",
    "csv": ".csv",
    "html": ".html",
    "png": ".png",
    "jpg": ".jpg",
    "jpeg": ".jpeg",
    "svg": ".svg",
}


def _safe_import(name: str, *args, **kwargs):
    """허용된 모듈만 import 허용하는 커스텀 __import__."""
    # 최상위 모듈명 추출
    top_module = name.split(".")[0]
    allowed_tops = {m.split(".")[0] for m in ALLOWED_MODULES}
    # 기본 내장 모듈도 허용
    allowed_tops.update({"builtins", "collections", "enum", "copy", "re", "string",
                          "typing", "functools", "itertools", "operator", "numbers"})

    if top_module in allowed_tops:
        return __builtins__["__import__"](name, *args, **kwargs) if isinstance(__builtins__, dict) \
            else __import__(name, *args, **kwargs)
    raise ImportError(f"Module '{name}' is not allowed for document generation")


def _validate_code(code: str) -> list[str]:
    """코드 보안 검증. 위반 사항 목록 반환."""
    violations = []
    for keyword in BLOCKED_KEYWORDS:
        if keyword in code:
            violations.append(f"Blocked keyword found: {keyword}")
    return violations


def generate_document(
    code: str,
    output_format: str = "pptx",
    output_filename: str = "",
) -> dict[str, Any]:
    """LLM이 생성한 Python 코드를 실행하여 문서 파일 생성.

    Args:
        code: Python 코드 (python-pptx, python-docx, reportlab, openpyxl 등 사용)
        output_format: 출력 포맷 (pptx, docx, pdf, xlsx, csv, html)
        output_filename: 출력 파일명 (미지정 시 자동 생성)

    Returns:
        {
            file_base64: base64 인코딩된 파일 내용,
            filename: 파일명,
            format: 포맷,
            size_bytes: 파일 크기,
            success: bool,
            error: 에러 메시지 (실패 시)
        }
    """
    fmt = output_format.lower().strip()
    if fmt not in FORMAT_EXTENSIONS:
        return {
            "success": False,
            "error": f"Unsupported format: {fmt}. Supported: {list(FORMAT_EXTENSIONS.keys())}",
        }

    # 보안 검증
    violations = _validate_code(code)
    if violations:
        return {
            "success": False,
            "error": f"Security violation: {'; '.join(violations)}",
        }

    ext = FORMAT_EXTENSIONS[fmt]
    filename = output_filename or f"generated_document{ext}"
    if not filename.endswith(ext):
        filename += ext

    try:
        # 임시 디렉토리에서 실행
        with tempfile.TemporaryDirectory() as tmpdir:
            output_path = os.path.join(tmpdir, filename)

            # 코드에 output_path를 주입
            exec_globals = {
                "__builtins__": {
                    k: v for k, v in (
                        __builtins__.items() if isinstance(__builtins__, dict)
                        else __builtins__.__dict__.items()
                    )
                    if k not in {"exec", "eval", "compile", "__import__"}
                },
                "OUTPUT_PATH": output_path,
                "OUTPUT_DIR": tmpdir,
            }
            # 안전한 import 함수 주입
            exec_globals["__builtins__"]["__import__"] = _safe_import

            exec(code, exec_globals)

            # 파일 존재 확인
            if not os.path.exists(output_path):
                # 코드가 다른 이름으로 저장했을 수 있으므로 디렉토리 내 파일 탐색
                files = [f for f in os.listdir(tmpdir) if f.endswith(ext)]
                if files:
                    output_path = os.path.join(tmpdir, files[0])
                    filename = files[0]
                else:
                    return {
                        "success": False,
                        "error": f"Code executed but no {ext} file was created. "
                                 f"Use OUTPUT_PATH variable for the output file path.",
                    }

            # 파일 읽기 + base64 인코딩
            with open(output_path, "rb") as f:
                content = f.read()

            file_base64 = base64.b64encode(content).decode("utf-8")
            size_bytes = len(content)

            logger.info("Document generated: %s (%d bytes)", filename, size_bytes)

            return {
                "file_base64": file_base64,
                "filename": filename,
                "format": fmt,
                "size_bytes": size_bytes,
                "success": True,
            }

    except Exception as e:
        logger.error("Document generation failed: %s", traceback.format_exc())
        return {
            "success": False,
            "error": f"Code execution failed: {type(e).__name__}: {str(e)}",
        }


def list_supported_formats() -> dict[str, Any]:
    """지원되는 문서 포맷 목록 반환."""
    formats = {
        "pptx": {"name": "PowerPoint", "library": "python-pptx", "description": "프레젠테이션 슬라이드 생성"},
        "docx": {"name": "Word", "library": "python-docx", "description": "문서/보고서 생성"},
        "pdf": {"name": "PDF", "library": "reportlab", "description": "PDF 문서 생성"},
        "xlsx": {"name": "Excel", "library": "openpyxl", "description": "스프레드시트 생성"},
        "csv": {"name": "CSV", "library": "csv (built-in)", "description": "CSV 데이터 파일 생성"},
        "html": {"name": "HTML", "library": "built-in", "description": "HTML 문서 생성"},
        "png": {"name": "PNG Image", "library": "Pillow", "description": "PNG 이미지 생성"},
        "jpg": {"name": "JPEG Image", "library": "Pillow", "description": "JPEG 이미지 생성"},
        "svg": {"name": "SVG Image", "library": "built-in", "description": "SVG 벡터 이미지 생성"},
    }

    # 설치 여부 확인
    check_map = {
        "pptx": "pptx", "docx": "docx", "pdf": "reportlab",
        "xlsx": "openpyxl", "png": "PIL", "jpg": "PIL",
    }
    for fmt, info in formats.items():
        if fmt in check_map:
            try:
                __import__(check_map[fmt])
                info["available"] = True
            except ImportError:
                info["available"] = False
        else:
            info["available"] = True  # built-in (csv, html, svg)

    return {"formats": formats, "success": True}
