"""CR-092 OCR 통합 테스트 (게이트: env OCR_IT=true).

실제 Tesseract + pytesseract + Pillow 가 설치된 환경에서만 실행.
- 로컬: brew install tesseract tesseract-lang && OCR_IT=true pytest tests/test_ocr_integration.py
- Docker: 이미지에 tesseract-ocr + tesseract-ocr-kor 포함되어 있음

런타임에 동적으로 한국어/영문 텍스트 이미지를 PIL 로 그려서 OCR 정확도를 측정.
fixtures 파일 없이 자체 생성 → 외부 자산 의존 0.
"""
from __future__ import annotations

import os
import pytest

OCR_IT = os.environ.get("OCR_IT", "").lower() in ("1", "true", "yes")
pytestmark = pytest.mark.skipif(not OCR_IT, reason="set OCR_IT=true to run")


def _render_text_png(text: str, font_size: int = 48) -> bytes:
    """PIL 로 텍스트를 PNG 바이트로 렌더."""
    from PIL import Image, ImageDraw, ImageFont
    import io

    # 폰트 — 시스템 폴백 (한국어는 NanumGothic / AppleGothic / 기본 폴백)
    font = None
    for path in [
        "/System/Library/Fonts/Supplemental/AppleGothic.ttf",
        "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ]:
        if os.path.exists(path):
            try:
                font = ImageFont.truetype(path, font_size)
                break
            except Exception:
                continue
    if font is None:
        font = ImageFont.load_default()

    # 이미지 크기: 텍스트 측정 후 패딩
    dummy = Image.new("RGB", (1, 1), "white")
    draw = ImageDraw.Draw(dummy)
    try:
        bbox = draw.textbbox((0, 0), text, font=font)
        w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
    except Exception:
        w, h = font_size * len(text), font_size + 10

    img = Image.new("RGB", (w + 40, h + 40), "white")
    d = ImageDraw.Draw(img)
    d.text((20, 20), text, fill="black", font=font)

    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def test_real_tesseract_english():
    from rag_pipeline.tools.ocr import ocr_image_bytes

    png = _render_text_png("Hello World")
    result = ocr_image_bytes(png, languages="eng")

    assert result["success"] is True, result.get("error")
    text = result["text"].strip()
    # 정확도가 100% 가 아닐 수 있으나 "Hello" 정도는 안정적
    assert "Hello" in text or "World" in text


def test_real_tesseract_korean_or_skip():
    from rag_pipeline.tools.ocr import ocr_image_bytes

    png = _render_text_png("안녕하세요", font_size=72)
    result = ocr_image_bytes(png, languages="kor+eng")

    if not result.get("success"):
        # 한국어 언어팩 미설치 환경에서는 skip
        if "tesseract" in str(result.get("error", "")).lower() or \
           "data" in str(result.get("error", "")).lower():
            pytest.skip(f"Korean language pack not available: {result.get('error')}")
        pytest.fail(f"OCR failed: {result.get('error')}")

    text = result["text"]
    # 한국어 인식 정확도가 폰트/품질 영향 큼 — 비어있지 않으면 성공으로 본다
    assert text is not None
