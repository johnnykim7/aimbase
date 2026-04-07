"""PY-017: Web Scraping Enhancement.

URL에서 콘텐츠를 추출하는 도구. basic/js_render/sitemap 모드 지원.
robots.txt 준수, rate limiting 적용.
"""

import logging
import time
import urllib.parse
import urllib.request
import urllib.robotparser
import xml.etree.ElementTree as ET
from html.parser import HTMLParser
from typing import Any

logger = logging.getLogger(__name__)

_DEFAULT_TIMEOUT_MS = 30_000
_RATE_LIMIT_SECONDS = 1.0
_USER_AGENT = "AimbaseBot/1.0"


class _SimpleHTMLTextExtractor(HTMLParser):
    """간단한 HTML → 텍스트 추출기."""

    _SKIP_TAGS = {"script", "style", "noscript", "head", "meta", "link"}

    def __init__(self):
        super().__init__()
        self._text_parts: list[str] = []
        self._skip_depth = 0
        self._title = ""
        self._in_title = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.lower() in self._SKIP_TAGS:
            self._skip_depth += 1
        if tag.lower() == "title":
            self._in_title = True

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() in self._SKIP_TAGS and self._skip_depth > 0:
            self._skip_depth -= 1
        if tag.lower() == "title":
            self._in_title = False

    def handle_data(self, data: str) -> None:
        if self._in_title:
            self._title = data.strip()
        if self._skip_depth == 0:
            cleaned = data.strip()
            if cleaned:
                self._text_parts.append(cleaned)

    @property
    def text(self) -> str:
        return " ".join(self._text_parts)

    @property
    def title(self) -> str:
        return self._title


def _check_robots_txt(url: str) -> bool:
    """robots.txt를 확인하여 크롤링 허용 여부 반환."""
    try:
        parsed = urllib.parse.urlparse(url)
        robots_url = f"{parsed.scheme}://{parsed.netloc}/robots.txt"
        rp = urllib.robotparser.RobotFileParser()
        rp.set_url(robots_url)
        rp.read()
        return rp.can_fetch(_USER_AGENT, url)
    except Exception as e:
        logger.warning("robots.txt 확인 실패 (%s), 크롤링 허용으로 처리: %s", url, e)
        return True


def _fetch_url(url: str, timeout_s: float) -> str:
    """URL에서 HTML 콘텐츠를 가져온다."""
    req = urllib.request.Request(
        url,
        headers={"User-Agent": _USER_AGENT},
    )
    with urllib.request.urlopen(req, timeout=timeout_s) as resp:
        charset = resp.headers.get_content_charset() or "utf-8"
        return resp.read().decode(charset)


def _extract_text_basic(html: str) -> tuple[str, str]:
    """HTML에서 텍스트와 title을 추출 (기본 파서 사용)."""
    try:
        from unstructured.partition.html import partition_html

        elements = partition_html(text=html)
        content = "\n".join(str(el) for el in elements)
        # title은 unstructured에서 직접 제공하지 않으므로 별도 추출
        extractor = _SimpleHTMLTextExtractor()
        extractor.feed(html)
        return content, extractor.title
    except ImportError:
        logger.info("unstructured 미설치, 기본 HTML 파서 사용")
        extractor = _SimpleHTMLTextExtractor()
        extractor.feed(html)
        return extractor.text, extractor.title


def _make_page_result(url: str, title: str, content: str) -> dict[str, Any]:
    """페이지 결과 딕셔너리 생성."""
    word_count = len(content.split()) if content else 0
    return {
        "url": url,
        "title": title,
        "content": content,
        "word_count": word_count,
    }


def _scrape_basic(url: str, timeout_s: float) -> dict[str, Any]:
    """기본 모드: urllib + 텍스트 추출."""
    if not _check_robots_txt(url):
        return {
            "pages": [],
            "total_pages": 0,
            "mode": "basic",
            "error": f"robots.txt에 의해 크롤링이 차단됨: {url}",
        }

    html = _fetch_url(url, timeout_s)
    content, title = _extract_text_basic(html)
    page = _make_page_result(url, title, content)

    return {
        "pages": [page],
        "total_pages": 1,
        "mode": "basic",
    }


def _scrape_js_render(url: str, timeout_s: float) -> dict[str, Any]:
    """JS 렌더링 모드: playwright 사용."""
    if not _check_robots_txt(url):
        return {
            "pages": [],
            "total_pages": 0,
            "mode": "js_render",
            "error": f"robots.txt에 의해 크롤링이 차단됨: {url}",
        }

    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        return {
            "pages": [],
            "total_pages": 0,
            "mode": "js_render",
            "error": "playwright 라이브러리가 설치되지 않았습니다. "
                     "'pip install playwright && playwright install' 후 재시도하세요.",
        }

    timeout_ms = int(timeout_s * 1000)

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        try:
            page = browser.new_page(user_agent=_USER_AGENT)
            page.goto(url, timeout=timeout_ms, wait_until="networkidle")
            title = page.title()
            html = page.content()
        finally:
            browser.close()

    content, _ = _extract_text_basic(html)
    page_result = _make_page_result(url, title, content)

    return {
        "pages": [page_result],
        "total_pages": 1,
        "mode": "js_render",
    }


def _scrape_sitemap(url: str, max_pages: int, timeout_s: float) -> dict[str, Any]:
    """사이트맵 모드: sitemap.xml 파싱 후 페이지별 크롤링."""
    parsed = urllib.parse.urlparse(url)
    sitemap_url = url if url.endswith(".xml") else f"{parsed.scheme}://{parsed.netloc}/sitemap.xml"

    try:
        sitemap_xml = _fetch_url(sitemap_url, timeout_s)
    except Exception as e:
        return {
            "pages": [],
            "total_pages": 0,
            "mode": "sitemap",
            "error": f"sitemap.xml 가져오기 실패: {e}",
        }

    # Parse sitemap XML
    try:
        root = ET.fromstring(sitemap_xml)
    except ET.ParseError as e:
        return {
            "pages": [],
            "total_pages": 0,
            "mode": "sitemap",
            "error": f"sitemap.xml 파싱 실패: {e}",
        }

    # Extract <loc> elements (handle namespace)
    ns = ""
    if root.tag.startswith("{"):
        ns = root.tag.split("}")[0] + "}"

    urls: list[str] = []
    for loc_el in root.iter(f"{ns}loc"):
        if loc_el.text:
            urls.append(loc_el.text.strip())

    if not urls:
        return {
            "pages": [],
            "total_pages": 0,
            "mode": "sitemap",
            "error": "sitemap.xml에서 URL을 찾을 수 없습니다.",
        }

    # Crawl pages up to max_pages
    pages: list[dict[str, Any]] = []
    for page_url in urls[:max_pages]:
        if not _check_robots_txt(page_url):
            logger.info("robots.txt 차단: %s", page_url)
            continue

        try:
            html = _fetch_url(page_url, timeout_s)
            content, title = _extract_text_basic(html)
            pages.append(_make_page_result(page_url, title, content))
        except Exception as e:
            logger.warning("페이지 크롤링 실패 (%s): %s", page_url, e)
            continue

        # Rate limiting
        if len(pages) < len(urls[:max_pages]):
            time.sleep(_RATE_LIMIT_SECONDS)

    return {
        "pages": pages,
        "total_pages": len(pages),
        "mode": "sitemap",
    }


def _scrape_firecrawl(url: str, timeout_s: float) -> dict[str, Any]:
    """CR-035 PRD-238: Firecrawl 모드 — JS 렌더링 + 구조화된 마크다운 출력.

    BIZ-062: API Key 미설정 시 js_render로 폴백.
    BIZ-063: Firecrawl 호출 실패 시 playwright로 폴백 (2단 폴백).
    """
    import os

    api_key = os.environ.get("FIRECRAWL_API_KEY", "")
    if not api_key:
        logger.info("FIRECRAWL_API_KEY 미설정 — js_render로 폴백")
        result = _scrape_js_render(url, timeout_s)
        result["fallback"] = "js_render (API key missing)"
        return result

    try:
        from firecrawl import FirecrawlApp
    except ImportError:
        logger.warning("firecrawl-py 미설치 — js_render로 폴백")
        result = _scrape_js_render(url, timeout_s)
        result["fallback"] = "js_render (firecrawl-py not installed)"
        return result

    base_url = os.environ.get("FIRECRAWL_BASE_URL")  # Self-hosted 지원
    try:
        app = FirecrawlApp(api_key=api_key, api_url=base_url) if base_url else FirecrawlApp(api_key=api_key)

        scrape_result = app.scrape_url(
            url,
            params={
                "formats": ["markdown", "html"],
                "timeout": int(timeout_s * 1000),
            },
        )

        content = scrape_result.get("markdown", "") or scrape_result.get("content", "")
        title = scrape_result.get("metadata", {}).get("title", "")
        page_result = _make_page_result(url, title, content)

        return {
            "pages": [page_result],
            "total_pages": 1,
            "mode": "firecrawl",
        }
    except Exception as e:
        logger.warning("Firecrawl 호출 실패 (%s) — js_render로 폴백: %s", url, e)
        result = _scrape_js_render(url, timeout_s)
        result["fallback"] = f"js_render (firecrawl error: {e})"
        return result


def scrape_url(
    url: str,
    mode: str = "basic",
    max_pages: int = 10,
    timeout_ms: int = _DEFAULT_TIMEOUT_MS,
) -> dict[str, Any]:
    """URL에서 콘텐츠를 스크래핑.

    Args:
        url: 스크래핑할 URL
        mode: "basic" (urllib), "js_render" (playwright), "firecrawl", "sitemap"
        max_pages: sitemap 모드에서 최대 크롤링 페이지 수
        timeout_ms: 요청 타임아웃 (밀리초)

    Returns:
        {pages: [{url, title, content, word_count}], total_pages: int, mode: str}
    """
    timeout_s = timeout_ms / 1000.0

    try:
        if mode == "basic":
            return _scrape_basic(url, timeout_s)
        elif mode == "js_render":
            return _scrape_js_render(url, timeout_s)
        elif mode == "firecrawl":
            return _scrape_firecrawl(url, timeout_s)
        elif mode == "sitemap":
            return _scrape_sitemap(url, max_pages, timeout_s)
        else:
            return {
                "pages": [],
                "total_pages": 0,
                "mode": mode,
                "error": f"지원하지 않는 모드: {mode}. 'basic', 'js_render', 'firecrawl', 'sitemap' 중 선택하세요.",
            }
    except urllib.error.URLError as e:
        return {
            "pages": [],
            "total_pages": 0,
            "mode": mode,
            "error": f"연결 오류: {e}",
        }
    except TimeoutError:
        return {
            "pages": [],
            "total_pages": 0,
            "mode": mode,
            "error": f"타임아웃 ({timeout_ms}ms 초과)",
        }
    except Exception as e:
        logger.error("스크래핑 실패: %s", e)
        return {
            "pages": [],
            "total_pages": 0,
            "mode": mode,
            "error": f"스크래핑 실패: {e}",
        }
