"""CR-092 OCR 단위 테스트.

원칙:
- 외부 바이너리(tesseract) 의존성을 피하기 위해 pytesseract / pdf2image 를 monkeypatch
- 검증 대상: 입력 검증, 분기 로직, 응답 스키마, 에러 매핑
- 실제 OCR 정확도는 통합 IT 단계(P5)에서 검증
"""
from __future__ import annotations

import io

import pytest

from rag_pipeline.tools import ocr as ocr_mod


# ── 언어 화이트리스트 (BIZ-106) ──────────────────────────────────────

class TestValidateLanguages:
    def test_default_kor_eng_accepted(self):
        ok, err = ocr_mod._validate_languages("kor+eng")
        assert ok is True
        assert err is None

    def test_single_eng_accepted(self):
        ok, err = ocr_mod._validate_languages("eng")
        assert ok is True

    def test_jpn_accepted(self):
        ok, err = ocr_mod._validate_languages("jpn")
        assert ok is True

    def test_unknown_language_rejected(self):
        ok, err = ocr_mod._validate_languages("klingon")
        assert ok is False
        assert "unsupported_language" in err
        assert "klingon" in err

    def test_partial_unknown_rejected(self):
        # 하나라도 화이트리스트 밖이면 거부
        ok, err = ocr_mod._validate_languages("kor+klingon")
        assert ok is False

    def test_empty_rejected(self):
        ok, err = ocr_mod._validate_languages("")
        assert ok is False
        assert err == "languages_empty"

    def test_whitespace_only_rejected(self):
        ok, err = ocr_mod._validate_languages("  +  ")
        assert ok is False


# ── ocr_image_bytes ─────────────────────────────────────────────────

class _FakeTesseractNotFound(Exception):
    """pytesseract.TesseractNotFoundError 모킹용."""


def _install_fake_pytesseract(monkeypatch, return_text: str = "",
                              raise_exc: Exception | None = None) -> types_ModuleType:
    """sys.modules['pytesseract'] 에 types.ModuleType 기반 가짜 모듈 설치."""
    import sys
    import types as _types
    pt = _types.ModuleType("pytesseract")
    pt.TesseractNotFoundError = _FakeTesseractNotFound
    state = {"last_lang": None}

    def _image_to_string(img, lang: str = "eng") -> str:
        state["last_lang"] = lang
        if raise_exc:
            raise raise_exc
        return return_text

    pt.image_to_string = _image_to_string
    pt._state = state
    monkeypatch.setitem(sys.modules, "pytesseract", pt)
    return pt


# 위 import 인용용 alias (forward ref 회피)
import types as _types_mod
types_ModuleType = _types_mod.ModuleType


@pytest.fixture
def fake_pil(monkeypatch):
    """PIL.Image.open 가 더미 이미지 객체를 반환하도록."""
    import sys
    import types

    class _FakeImage:
        pass

    image_submod = types.ModuleType("PIL.Image")
    image_submod.open = lambda _buf: _FakeImage()

    pil_mod = types.ModuleType("PIL")
    pil_mod.Image = image_submod

    monkeypatch.setitem(sys.modules, "PIL", pil_mod)
    monkeypatch.setitem(sys.modules, "PIL.Image", image_submod)
    return image_submod


class TestOcrImageBytes:
    def test_empty_bytes_returns_error(self):
        result = ocr_mod.ocr_image_bytes(b"", languages="kor+eng")
        assert result["success"] is False
        assert result["error"] == "empty_image_bytes"

    def test_invalid_language_returns_error(self):
        result = ocr_mod.ocr_image_bytes(b"\x89PNG\r\n", languages="klingon")
        assert result["success"] is False
        assert "unsupported_language" in result["error"]

    def test_success_path(self, monkeypatch, fake_pil):
        pt = _install_fake_pytesseract(monkeypatch, return_text="안녕하세요 hello")
        result = ocr_mod.ocr_image_bytes(b"\x89PNG\r\nfake", languages="kor+eng")
        assert result["success"] is True
        assert result["text"] == "안녕하세요 hello"
        assert result["languages"] == "kor+eng"
        assert pt._state["last_lang"] == "kor+eng"

    def test_tesseract_not_installed(self, monkeypatch, fake_pil):
        _install_fake_pytesseract(monkeypatch, raise_exc=_FakeTesseractNotFound("not found"))
        result = ocr_mod.ocr_image_bytes(b"\x89PNG\r\nfake", languages="eng")
        assert result["success"] is False
        assert result["error"] == "tesseract_not_installed"

    def test_generic_failure(self, monkeypatch, fake_pil):
        _install_fake_pytesseract(monkeypatch, raise_exc=RuntimeError("boom"))
        result = ocr_mod.ocr_image_bytes(b"\x89PNG\r\nfake", languages="eng")
        assert result["success"] is False
        assert result["error"].startswith("ocr_failed:")


# ── ocr_pdf_bytes ───────────────────────────────────────────────────

class _FakePage:
    def __init__(self, text: str):
        self._text = text
        self.width = 612
        self.height = 792
        self.images = []

    def extract_text(self) -> str:
        return self._text

    def extract_tables(self):
        return []


class _FakePdfPlumberPdf:
    def __init__(self, page_texts: list[str]):
        self.pages = [_FakePage(t) for t in page_texts]

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


@pytest.fixture
def fake_pdf_libs(monkeypatch):
    """pdf2image.convert_from_bytes + pdfplumber 한꺼번에 모킹.

    sys.modules 등록 시 모듈로 인식되도록 types.ModuleType 사용.
    """
    import sys
    import types

    def install(page_texts: list[str], ocr_text: str = "OCR_RESULT",
                convert_raise: Exception | None = None,
                tesseract_raise: Exception | None = None):
        # pdfplumber 모킹
        pdfplumber_mod = types.ModuleType("pdfplumber")

        def _open(_buf):
            return _FakePdfPlumberPdf(page_texts)

        pdfplumber_mod.open = _open
        monkeypatch.setitem(sys.modules, "pdfplumber", pdfplumber_mod)

        # pdf2image 모킹
        pdf2image_mod = types.ModuleType("pdf2image")

        def _convert_from_bytes(_bytes, dpi=300, last_page=None):
            if convert_raise:
                raise convert_raise
            count = last_page if last_page is not None else len(page_texts)
            return [object() for _ in range(count)]

        pdf2image_mod.convert_from_bytes = _convert_from_bytes
        monkeypatch.setitem(sys.modules, "pdf2image", pdf2image_mod)

        # pytesseract 모킹 — 모듈 ModuleType 에 속성 부여
        pt_mod = types.ModuleType("pytesseract")
        pt_mod.TesseractNotFoundError = _FakeTesseractNotFound
        call_state = {"raise_exc": tesseract_raise, "return_text": ocr_text,
                      "last_lang": None}

        def _image_to_string(img, lang="eng"):
            call_state["last_lang"] = lang
            if call_state["raise_exc"]:
                raise call_state["raise_exc"]
            return call_state["return_text"]

        pt_mod.image_to_string = _image_to_string
        pt_mod._call_state = call_state  # 테스트가 동적 교체할 수 있도록 노출
        monkeypatch.setitem(sys.modules, "pytesseract", pt_mod)
        return pt_mod

    return install


class TestOcrPdfBytes:
    def test_empty_bytes(self):
        result = ocr_mod.ocr_pdf_bytes(b"")
        assert result["success"] is False
        assert result["error"] == "empty_pdf_bytes"

    def test_invalid_language(self):
        result = ocr_mod.ocr_pdf_bytes(b"%PDF-1.4 fake", languages="klingon")
        assert result["success"] is False

    def test_embedded_text_preferred(self, fake_pdf_libs):
        # 페이지 모두 텍스트 박혀있음 → OCR 호출 0건, source=embedded
        fake_pdf_libs(
            page_texts=["This is a long enough embedded text paragraph one.",
                        "Page two also has embedded text content here."],
        )
        result = ocr_mod.ocr_pdf_bytes(b"%PDF-1.4 fake", languages="eng")
        assert result["success"] is True
        assert result["page_count"] == 2
        assert all(p["source"] == "embedded" for p in result["pages"])
        assert result["truncated"] is False

    def test_empty_pages_ocred(self, fake_pdf_libs):
        # 빈 페이지 → OCR 분기로 들어감
        fake_pdf_libs(
            page_texts=["", "   "],
            ocr_text="OCR_TEXT",
        )
        result = ocr_mod.ocr_pdf_bytes(b"%PDF-1.4 fake", languages="kor+eng")
        assert result["success"] is True
        assert result["page_count"] == 2
        assert all(p["source"] == "ocr" for p in result["pages"])
        assert all(p["text"] == "OCR_TEXT" for p in result["pages"])

    def test_mixed_pages(self, fake_pdf_libs):
        fake_pdf_libs(
            page_texts=["Long embedded text right here please.", ""],
            ocr_text="OCR_FOR_PAGE_2",
        )
        result = ocr_mod.ocr_pdf_bytes(b"%PDF-1.4 fake")
        assert result["success"] is True
        sources = [p["source"] for p in result["pages"]]
        assert sources == ["embedded", "ocr"]

    def test_max_pages_truncation(self, fake_pdf_libs):
        # BIZ-105: 100p PDF, max_pages=3 → truncated=true, page_count=3
        fake_pdf_libs(page_texts=[""] * 100, ocr_text="X")
        result = ocr_mod.ocr_pdf_bytes(b"%PDF-1.4 fake", max_pages=3)
        assert result["success"] is True
        assert result["truncated"] is True
        assert result["total_pages"] == 100
        assert result["page_count"] == 3

    def test_pdf2image_failure_returns_error(self, fake_pdf_libs):
        fake_pdf_libs(
            page_texts=[""],
            convert_raise=RuntimeError("poppler missing"),
        )
        result = ocr_mod.ocr_pdf_bytes(b"%PDF-1.4 fake")
        assert result["success"] is False
        assert result["error"].startswith("pdf2image_failed:")

    def test_tesseract_not_installed(self, fake_pdf_libs):
        fake_pdf_libs(
            page_texts=[""],
            tesseract_raise=_FakeTesseractNotFound("nope"),
        )
        result = ocr_mod.ocr_pdf_bytes(b"%PDF-1.4 fake")
        assert result["success"] is False
        assert result["error"] == "tesseract_not_installed"

    def test_per_page_failure_continues(self, fake_pdf_libs):
        # 1페이지는 일반 예외, 2페이지는 OK — warnings 누적되고 success 유지
        fake_pdf_libs(page_texts=["", ""], ocr_text="OK")
        import sys
        pt_mod = sys.modules["pytesseract"]
        calls = {"n": 0}
        original = pt_mod.image_to_string

        def flaky(img, lang="eng"):
            calls["n"] += 1
            if calls["n"] == 1:
                raise RuntimeError("page1 boom")
            return original(img, lang=lang)

        pt_mod.image_to_string = flaky

        result = ocr_mod.ocr_pdf_bytes(b"%PDF-1.4 fake")
        assert result["success"] is True, f"unexpected error: {result.get('error')}"
        assert len(result["warnings"]) == 1
        assert "page_1_ocr_failed" in result["warnings"][0]
        assert result["pages"][0]["source"] == "failed"
        assert result["pages"][1]["source"] == "ocr"

    def test_total_characters_aggregated(self, fake_pdf_libs):
        fake_pdf_libs(page_texts=["abc", ""], ocr_text="defgh")
        result = ocr_mod.ocr_pdf_bytes(b"%PDF-1.4 fake")
        # embedded "abc" 는 page_text_min_chars(20) 미달 → OCR 경로로 가서 "defgh"
        assert result["pages"][0]["source"] == "ocr"
        assert result["pages"][0]["text"] == "defgh"
        assert result["total_characters"] == 10  # 5 + 5


# ── read_pdf 라우팅 (CR-092 통합) ───────────────────────────────────

class TestReadPdfRouting:
    """`read_pdf` 가 ocr_enabled 분기에 따라 ocr_pdf_bytes 로 위임하는지 검증."""

    def test_ocr_disabled_uses_existing_path(self, fake_pdf_libs):
        # ocr_enabled=False 면 ocr_pdf_bytes 가 아니라 _read_pdf_pdfplumber 경로
        fake_pdf_libs(page_texts=["embedded text here that is long enough."], ocr_text="SHOULD_NOT_USE")
        from rag_pipeline.tools.document_reader import read_pdf
        import base64 as b64
        fake_pdf = b64.b64encode(b"%PDF-1.4 fake").decode("ascii")

        result = read_pdf(file_base64=fake_pdf, ocr_enabled=False)
        assert result["success"] is True
        # 기존 응답 스키마: page_data 안에 width/height 존재
        assert "width" in result["pages"][0]

    def test_ocr_enabled_routes_to_ocr_pdf_bytes(self, fake_pdf_libs):
        fake_pdf_libs(page_texts=[""], ocr_text="OCR_OUTPUT")
        from rag_pipeline.tools.document_reader import read_pdf
        import base64 as b64
        fake_pdf = b64.b64encode(b"%PDF-1.4 fake").decode("ascii")

        result = read_pdf(file_base64=fake_pdf, ocr_enabled=True, ocr_languages="eng")
        assert result["success"] is True
        # OCR 분기 응답 스키마: source 필드
        assert result["pages"][0]["source"] == "ocr"
        assert result["pages"][0]["text"] == "OCR_OUTPUT"
        assert result["languages"] == "eng"

    def test_ocr_enabled_invalid_language(self, fake_pdf_libs):
        fake_pdf_libs(page_texts=[""])
        from rag_pipeline.tools.document_reader import read_pdf
        import base64 as b64
        fake_pdf = b64.b64encode(b"%PDF-1.4 fake").decode("ascii")

        result = read_pdf(file_base64=fake_pdf, ocr_enabled=True, ocr_languages="klingon")
        assert result["success"] is False

    def test_invalid_base64(self):
        from rag_pipeline.tools.document_reader import read_pdf
        result = read_pdf(file_base64="!!!not-base64!!!", ocr_enabled=True)
        assert result["success"] is False
        assert "Invalid base64" in result.get("error", "")
