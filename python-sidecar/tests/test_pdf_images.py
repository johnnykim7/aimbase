"""CR-095: pdf_to_images (비전 파싱용 PDF→이미지화) 단위 테스트.

외부 바이너리(poppler) 의존을 피하기 위해 pdf2image.convert_from_bytes 를 monkeypatch.
parse_page_range 는 순수 함수라 의존성 없이 직접 검증.
"""
from __future__ import annotations

import sys
import types

import pytest

from rag_pipeline.tools.pdf_images import parse_page_range, pdf_to_images_bytes


# ---------------------------------------------------------------------------
# parse_page_range — openclaude parsePDFPageRange 포팅
# ---------------------------------------------------------------------------
class TestParsePageRange:
    def test_none_and_empty(self):
        assert parse_page_range(None) is None
        assert parse_page_range("") is None
        assert parse_page_range("   ") is None

    def test_single_page(self):
        assert parse_page_range("5") == (5, 5)

    def test_closed_range(self):
        assert parse_page_range("1-10") == (1, 10)

    def test_open_ended(self):
        assert parse_page_range("3-") == (3, None)

    @pytest.mark.parametrize("bad", ["0", "2-1", "abc", "-5", "0-3", "1-0"])
    def test_invalid_raises(self, bad):
        with pytest.raises(ValueError):
            parse_page_range(bad)


# ---------------------------------------------------------------------------
# pdf_to_images_bytes — pdf2image 모킹
# ---------------------------------------------------------------------------
class _FakeImage:
    """PIL Image 흉내 — .mode, .convert(), .save() 만 제공."""
    def __init__(self, mode="RGB"):
        self.mode = mode

    def convert(self, mode):
        return _FakeImage(mode)

    def save(self, buf, format="JPEG", quality=80):
        buf.write(b"\xff\xd8\xff\xe0FAKEJPEG")  # JPEG SOI + 더미


@pytest.fixture
def fake_pdf2image(monkeypatch):
    def install(page_count: int = 2, convert_raise: Exception | None = None,
                mode: str = "RGB", capture: dict | None = None):
        mod = types.ModuleType("pdf2image")

        def _convert_from_bytes(_bytes, dpi=200, first_page=None, last_page=None, fmt=None):
            if capture is not None:
                capture.update(dpi=dpi, first_page=first_page, last_page=last_page, fmt=fmt)
            if convert_raise:
                raise convert_raise
            fp = first_page or 1
            lp = last_page if last_page is not None else page_count
            n = max(0, min(page_count, lp) - fp + 1)
            return [_FakeImage(mode) for _ in range(n)]

        mod.convert_from_bytes = _convert_from_bytes
        monkeypatch.setitem(sys.modules, "pdf2image", mod)

    return install


def test_empty_bytes_fails():
    r = pdf_to_images_bytes(b"")
    assert r["success"] is False
    assert r["error"] == "empty_pdf_bytes"


def test_invalid_pages_fails(fake_pdf2image):
    fake_pdf2image(page_count=3)
    r = pdf_to_images_bytes(b"%PDF-1.4 fake", pages="2-1")
    assert r["success"] is False
    assert "invalid page range" in r["error"]


def test_all_pages_success(fake_pdf2image):
    fake_pdf2image(page_count=2)
    r = pdf_to_images_bytes(b"%PDF-1.4 fake")
    assert r["success"] is True
    assert r["page_count"] == 2
    assert r["images"][0]["media_type"] == "image/jpeg"
    assert r["images"][0]["page_number"] == 1
    assert r["images"][1]["page_number"] == 2
    # base64 디코드 가능해야 함
    import base64
    assert base64.b64decode(r["images"][0]["data"])


def test_page_range_passed_to_poppler(fake_pdf2image):
    cap: dict = {}
    fake_pdf2image(page_count=10, capture=cap)
    r = pdf_to_images_bytes(b"%PDF-1.4 fake", pages="3-5", dpi=150)
    assert r["success"] is True
    assert cap["first_page"] == 3
    assert cap["last_page"] == 5
    assert cap["dpi"] == 150
    # page_number 는 first_page 기준 오프셋
    assert r["images"][0]["page_number"] == 3


def test_max_pages_caps_range(fake_pdf2image):
    cap: dict = {}
    fake_pdf2image(page_count=100, capture=cap)
    r = pdf_to_images_bytes(b"%PDF-1.4 fake", max_pages=5)
    assert r["success"] is True
    # 전체 요청이지만 max_pages=5 로 last_page 가 잘림
    assert cap["first_page"] == 1
    assert cap["last_page"] == 5
    assert r["truncated"] is True


def test_open_ended_range_respects_max_pages(fake_pdf2image):
    cap: dict = {}
    fake_pdf2image(page_count=100, capture=cap)
    r = pdf_to_images_bytes(b"%PDF-1.4 fake", pages="10-", max_pages=3)
    assert r["success"] is True
    assert cap["first_page"] == 10
    assert cap["last_page"] == 12  # 10 + 3 - 1


def test_convert_failure_returns_error(fake_pdf2image):
    fake_pdf2image(convert_raise=RuntimeError("poppler boom"))
    r = pdf_to_images_bytes(b"%PDF-1.4 fake")
    assert r["success"] is False
    assert "pdf2image_failed" in r["error"]


def test_rgba_mode_converted(fake_pdf2image):
    """RGBA/P 모드는 JPEG 저장 전 RGB 변환 (예외 없이 통과)."""
    fake_pdf2image(page_count=1, mode="RGBA")
    r = pdf_to_images_bytes(b"%PDF-1.4 fake")
    assert r["success"] is True
    assert r["page_count"] == 1
