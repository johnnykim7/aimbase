"""PDF → 페이지 이미지화 (CR-095). pdf2image(poppler) 기반.

Vision 파싱용. OCR/텍스트 추출(CR-092 ocr.py) 과 다르다 — 여기선 텍스트를 뽑지 않고
PDF 페이지를 그대로 JPEG 로 렌더해 base64 로 반환한다. LLM 이 그 이미지를 비전으로 직접 본다.

openclaude FileReadTool 의 extractPDFPages(pdftoppm -jpeg -r 100) 1:1 대응:
- 100 DPI JPEG
- pages 파라미터("1-5", "3", "10-") 로 페이지 범위 선택
- 페이지 상한(BIZ-105 와 동형, 기본 20) 으로 폭주 방어

상수는 BE(application.yml `aimbase.pdf.*`) 와 호출부에서 강제. 여기 기본값은 안전망.
"""
from __future__ import annotations

import io
from typing import Any

_DEFAULT_DPI = 100  # openclaude pdftoppm -r 100 동일
_DEFAULT_MAX_PAGES = 20  # PDF_MAX_PAGES_PER_READ
_JPEG_QUALITY = 80


def pdf_page_count_bytes(pdf_bytes: bytes) -> int | None:
    """PDF 전체 페이지 수 (변환 없이 빠르게). openclaude getPDFPageCount(pdfinfo) 대응.

    이미지화 전 10페이지 분할 가드 판단에 쓴다. pdfplumber 로 메타만 읽어 빠름.
    실패 시 None (호출부가 가드를 건너뛰고 진행).
    """
    if not pdf_bytes:
        return None
    try:
        import pdfplumber
    except ImportError:
        return None
    try:
        with pdfplumber.open(io.BytesIO(pdf_bytes)) as pdf:
            return len(pdf.pages)
    except Exception:
        return None


def parse_page_range(pages: str | None) -> tuple[int | None, int | None] | None:
    """openclaude parsePDFPageRange 포팅. 1-indexed.

    "5"   → (5, 5)
    "1-10"→ (1, 10)
    "3-"  → (3, None)  (열린 끝)
    None/"" → None (전체)

    잘못된 입력(0, 역순, 비숫자) → ValueError.
    """
    if pages is None:
        return None
    trimmed = pages.strip()
    if not trimmed:
        return None

    if trimmed.endswith("-"):
        first = _parse_int(trimmed[:-1])
        if first is None or first < 1:
            raise ValueError(f"invalid page range: {pages!r}")
        return (first, None)

    if "-" not in trimmed:
        page = _parse_int(trimmed)
        if page is None or page < 1:
            raise ValueError(f"invalid page range: {pages!r}")
        return (page, page)

    dash = trimmed.index("-")
    first = _parse_int(trimmed[:dash])
    last = _parse_int(trimmed[dash + 1:])
    if first is None or last is None or first < 1 or last < 1 or last < first:
        raise ValueError(f"invalid page range: {pages!r}")
    return (first, last)


def _parse_int(s: str) -> int | None:
    try:
        return int(s.strip())
    except (ValueError, TypeError):
        return None


def pdf_to_images_bytes(
    pdf_bytes: bytes,
    pages: str | None = None,
    dpi: int = _DEFAULT_DPI,
    max_pages: int = _DEFAULT_MAX_PAGES,
) -> dict[str, Any]:
    """PDF → 페이지별 JPEG base64.

    Args:
        pdf_bytes: PDF 원본 바이트
        pages: 페이지 범위("1-5" 등). None 이면 전체(단 max_pages 상한).
        dpi: 렌더 해상도 (기본 100, openclaude 동일)
        max_pages: 한 번에 변환할 최대 페이지 수 (폭주 방어)

    Returns:
        성공: {success, images:[{page_number, media_type, data(base64)}],
               page_count, total_pages, truncated, dpi}
        실패: {success: False, error}
    """
    if not pdf_bytes:
        return {"success": False, "error": "empty_pdf_bytes"}

    try:
        page_range = parse_page_range(pages)
    except ValueError as e:
        return {"success": False, "error": str(e)}

    try:
        from pdf2image import convert_from_bytes
    except ImportError as e:
        return {"success": False, "error": f"pdf2image_missing: {e}"}

    total_pages = pdf_page_count_bytes(pdf_bytes)  # 변환 전 전체 페이지 수 (가드/메타용)

    # 페이지 범위 → first/last (1-indexed). max_pages 로 상한 강제.
    if page_range is None:
        first_page = 1
        last_page = max_pages
    else:
        first_page, last_page = page_range
        if last_page is None:
            last_page = first_page + max_pages - 1
        # 요청 범위가 max_pages 초과면 잘라낸다.
        if last_page - first_page + 1 > max_pages:
            last_page = first_page + max_pages - 1

    try:
        images = convert_from_bytes(
            pdf_bytes,
            dpi=dpi,
            first_page=first_page,
            last_page=last_page,
            fmt="jpeg",
        )
    except Exception as e:
        return {"success": False, "error": f"pdf2image_failed: {e}"}

    out: list[dict[str, Any]] = []
    import base64 as _b64
    for offset, img in enumerate(images):
        buf = io.BytesIO()
        # RGBA → RGB (JPEG 는 알파 미지원)
        if img.mode in ("RGBA", "P"):
            img = img.convert("RGB")
        img.save(buf, format="JPEG", quality=_JPEG_QUALITY)
        out.append({
            "page_number": first_page + offset,
            "media_type": "image/jpeg",
            "data": _b64.b64encode(buf.getvalue()).decode("ascii"),
        })

    # truncated: 범위 미지정인데 전체 페이지가 변환분보다 많으면 잘린 것.
    if total_pages is not None:
        truncated = page_range is None and total_pages > len(out)
    else:
        truncated = page_range is None and len(out) >= max_pages

    return {
        "success": True,
        "images": out,
        "page_count": len(out),
        "total_pages": total_pages,
        "truncated": truncated,
        "dpi": dpi,
    }
