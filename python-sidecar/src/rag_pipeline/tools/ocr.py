"""OCR helpers (CR-092). Tesseract via pytesseract + pdf2image.

전통적 OCR 경로. Vision 모델(CR-061) 과 분리된 결정론·오프라인 추출.
- ocr_image_bytes: 단일 이미지(JPG/PNG/etc.) → 텍스트
- ocr_pdf_bytes: PDF → 페이지별 텍스트. 텍스트 박힌 페이지는 우선 사용, 빈 페이지만 OCR.

BIZ-105: max_pages=50, BIZ-106: 언어 화이트리스트, BIZ-107: BE fallback 50자 임계는 호출부에서 적용.
"""
from __future__ import annotations

import io
from typing import Any

_DEFAULT_LANGUAGES = "kor+eng"
_DEFAULT_MAX_PAGES = 50
_DEFAULT_DPI = 300
_DEFAULT_PAGE_TEXT_MIN_CHARS = 20

_LANGUAGE_WHITELIST = {
    "eng", "kor", "jpn", "chi_sim", "chi_tra",
    "fra", "deu", "spa",
}


def _validate_languages(languages: str) -> tuple[bool, str | None]:
    """`kor+eng` 같은 + 결합 lang code 검증."""
    if not languages:
        return False, "languages_empty"
    parts = [p.strip() for p in languages.split("+") if p.strip()]
    if not parts:
        return False, "languages_empty"
    for p in parts:
        if p not in _LANGUAGE_WHITELIST:
            return False, f"unsupported_language: {p}. allowed={sorted(_LANGUAGE_WHITELIST)}"
    return True, None


def ocr_image_bytes(image_bytes: bytes, languages: str = _DEFAULT_LANGUAGES) -> dict[str, Any]:
    """단일 이미지 → 텍스트.

    Returns:
        {text, success, languages} 또는 {success: False, error}
    """
    ok, err = _validate_languages(languages)
    if not ok:
        return {"success": False, "error": err}
    if not image_bytes:
        return {"success": False, "error": "empty_image_bytes"}
    try:
        import pytesseract
        from PIL import Image
    except ImportError as e:
        return {"success": False, "error": f"ocr_dependencies_missing: {e}"}
    try:
        img = Image.open(io.BytesIO(image_bytes))
        text = pytesseract.image_to_string(img, lang=languages)
        return {"text": text, "success": True, "languages": languages}
    except pytesseract.TesseractNotFoundError:
        return {"success": False, "error": "tesseract_not_installed"}
    except Exception as e:
        return {"success": False, "error": f"ocr_failed: {e}"}


def ocr_pdf_bytes(
    pdf_bytes: bytes,
    languages: str = _DEFAULT_LANGUAGES,
    max_pages: int = _DEFAULT_MAX_PAGES,
    dpi: int = _DEFAULT_DPI,
    page_text_min_chars: int = _DEFAULT_PAGE_TEXT_MIN_CHARS,
) -> dict[str, Any]:
    """PDF → 페이지별 OCR 텍스트.

    텍스트 박힌 페이지(>=page_text_min_chars)는 pdfplumber.extract_text 우선,
    빈 페이지만 pdf2image 로 렌더 후 pytesseract OCR.
    """
    ok, err = _validate_languages(languages)
    if not ok:
        return {"success": False, "error": err}
    if not pdf_bytes:
        return {"success": False, "error": "empty_pdf_bytes"}
    try:
        import pytesseract
        from pdf2image import convert_from_bytes
        import pdfplumber
    except ImportError as e:
        return {"success": False, "error": f"ocr_dependencies_missing: {e}"}

    pages: list[dict[str, Any]] = []
    warnings: list[str] = []
    truncated = False

    try:
        with pdfplumber.open(io.BytesIO(pdf_bytes)) as pdf:
            total_pages = len(pdf.pages)
            if total_pages > max_pages:
                truncated = True
                target_pages = max_pages
            else:
                target_pages = total_pages

            try:
                images = convert_from_bytes(pdf_bytes, dpi=dpi, last_page=target_pages)
            except Exception as e:
                return {"success": False, "error": f"pdf2image_failed: {e}"}

            for idx in range(target_pages):
                page = pdf.pages[idx]
                embedded = page.extract_text() or ""
                if len(embedded.strip()) >= page_text_min_chars:
                    pages.append({
                        "page_number": idx + 1,
                        "text": embedded,
                        "source": "embedded",
                    })
                    continue
                try:
                    img = images[idx]
                    ocr_text = pytesseract.image_to_string(img, lang=languages)
                    pages.append({
                        "page_number": idx + 1,
                        "text": ocr_text,
                        "source": "ocr",
                    })
                except pytesseract.TesseractNotFoundError:
                    return {"success": False, "error": "tesseract_not_installed"}
                except Exception as e:
                    warnings.append(f"page_{idx + 1}_ocr_failed: {e}")
                    pages.append({
                        "page_number": idx + 1,
                        "text": "",
                        "source": "failed",
                    })
    except Exception as e:
        return {"success": False, "error": f"pdfplumber_failed: {e}"}

    return {
        "pages": pages,
        "page_count": len(pages),
        "total_pages": total_pages,
        "truncated": truncated,
        "languages": languages,
        "warnings": warnings,
        "total_characters": sum(len(p.get("text", "")) for p in pages),
        "success": True,
    }
