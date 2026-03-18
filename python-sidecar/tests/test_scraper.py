"""Tests for PY-017: Web Scraping Enhancement."""

from unittest.mock import MagicMock, patch, PropertyMock
import urllib.error

import pytest


class TestScrapeBasicMode:
    """기본 모드 스크래핑 테스트."""

    @patch("rag_pipeline.tools.scraper._check_robots_txt", return_value=True)
    @patch("rag_pipeline.tools.scraper._fetch_url")
    def test_basic_scrape(self, mock_fetch, mock_robots):
        """기본 모드로 URL 스크래핑."""
        from rag_pipeline.tools.scraper import scrape_url

        mock_fetch.return_value = "<html><head><title>Test Page</title></head><body><p>Hello World</p></body></html>"

        result = scrape_url("https://example.com", mode="basic")

        assert result["mode"] == "basic"
        assert result["total_pages"] == 1
        assert len(result["pages"]) == 1
        assert result["pages"][0]["url"] == "https://example.com"
        assert result["pages"][0]["word_count"] > 0

    @patch("rag_pipeline.tools.scraper._check_robots_txt", return_value=False)
    def test_blocked_by_robots(self, mock_robots):
        """robots.txt에 의해 차단."""
        from rag_pipeline.tools.scraper import scrape_url

        result = scrape_url("https://example.com/private", mode="basic")

        assert result["total_pages"] == 0
        assert "error" in result
        assert "robots.txt" in result["error"]

    @patch("rag_pipeline.tools.scraper._check_robots_txt", return_value=True)
    @patch("rag_pipeline.tools.scraper._fetch_url", side_effect=urllib.error.URLError("Connection refused"))
    def test_connection_error(self, mock_fetch, mock_robots):
        """연결 오류 처리."""
        from rag_pipeline.tools.scraper import scrape_url

        result = scrape_url("https://unreachable.example.com", mode="basic")

        assert result["total_pages"] == 0
        assert "error" in result


class TestScrapeJsRenderMode:
    """JS 렌더링 모드 테스트."""

    @patch("rag_pipeline.tools.scraper._check_robots_txt", return_value=True)
    def test_missing_playwright(self, mock_robots):
        """playwright 미설치 시 에러."""
        import sys

        with patch.dict("sys.modules", {"playwright": None, "playwright.sync_api": None}):
            if "rag_pipeline.tools.scraper" in sys.modules:
                del sys.modules["rag_pipeline.tools.scraper"]

            from rag_pipeline.tools.scraper import scrape_url

            result = scrape_url("https://example.com", mode="js_render")

        assert result["mode"] == "js_render"
        assert "error" in result
        assert "playwright" in result["error"]

    @patch("rag_pipeline.tools.scraper._check_robots_txt", return_value=False)
    def test_js_render_blocked_by_robots(self, mock_robots):
        """JS 모드에서도 robots.txt 준수."""
        from rag_pipeline.tools.scraper import scrape_url

        result = scrape_url("https://example.com", mode="js_render")

        assert result["total_pages"] == 0
        assert "robots.txt" in result["error"]


class TestScrapeSitemapMode:
    """사이트맵 모드 테스트."""

    SAMPLE_SITEMAP = """<?xml version="1.0" encoding="UTF-8"?>
    <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
        <url><loc>https://example.com/page1</loc></url>
        <url><loc>https://example.com/page2</loc></url>
        <url><loc>https://example.com/page3</loc></url>
    </urlset>"""

    @patch("rag_pipeline.tools.scraper._check_robots_txt", return_value=True)
    @patch("rag_pipeline.tools.scraper._fetch_url")
    @patch("rag_pipeline.tools.scraper.time")
    def test_sitemap_crawl(self, mock_time, mock_fetch, mock_robots):
        """사이트맵 URL들을 순회하며 크롤링."""
        from rag_pipeline.tools.scraper import scrape_url

        def side_effect(url, timeout):
            if url.endswith(".xml"):
                return self.SAMPLE_SITEMAP
            return f"<html><head><title>{url}</title></head><body>Content of {url}</body></html>"

        mock_fetch.side_effect = side_effect

        result = scrape_url("https://example.com/sitemap.xml", mode="sitemap", max_pages=2)

        assert result["mode"] == "sitemap"
        assert result["total_pages"] == 2
        assert len(result["pages"]) == 2

    @patch("rag_pipeline.tools.scraper._check_robots_txt", return_value=True)
    @patch("rag_pipeline.tools.scraper._fetch_url", side_effect=Exception("Network error"))
    def test_sitemap_fetch_failure(self, mock_fetch, mock_robots):
        """sitemap.xml 가져오기 실패."""
        from rag_pipeline.tools.scraper import scrape_url

        result = scrape_url("https://example.com/sitemap.xml", mode="sitemap")

        assert result["total_pages"] == 0
        assert "error" in result

    @patch("rag_pipeline.tools.scraper._check_robots_txt", return_value=True)
    @patch("rag_pipeline.tools.scraper._fetch_url")
    def test_invalid_sitemap_xml(self, mock_fetch, mock_robots):
        """잘못된 XML 파싱 실패."""
        from rag_pipeline.tools.scraper import scrape_url

        mock_fetch.return_value = "not valid xml <<<"

        result = scrape_url("https://example.com/sitemap.xml", mode="sitemap")

        assert result["total_pages"] == 0
        assert "error" in result


class TestUnsupportedMode:
    """지원하지 않는 모드 테스트."""

    def test_unknown_mode(self):
        """알 수 없는 모드 에러."""
        from rag_pipeline.tools.scraper import scrape_url

        result = scrape_url("https://example.com", mode="unknown")

        assert "error" in result
        assert "지원하지 않는 모드" in result["error"]


class TestCheckRobotsTxt:
    """robots.txt 확인 테스트."""

    @patch("rag_pipeline.tools.scraper.urllib.robotparser.RobotFileParser")
    def test_robots_allowed(self, mock_rp_cls):
        """robots.txt 허용."""
        from rag_pipeline.tools.scraper import _check_robots_txt

        mock_rp = MagicMock()
        mock_rp.can_fetch.return_value = True
        mock_rp_cls.return_value = mock_rp

        assert _check_robots_txt("https://example.com/page") is True

    @patch("rag_pipeline.tools.scraper.urllib.robotparser.RobotFileParser")
    def test_robots_denied(self, mock_rp_cls):
        """robots.txt 차단."""
        from rag_pipeline.tools.scraper import _check_robots_txt

        mock_rp = MagicMock()
        mock_rp.can_fetch.return_value = False
        mock_rp_cls.return_value = mock_rp

        assert _check_robots_txt("https://example.com/private") is False

    @patch("rag_pipeline.tools.scraper.urllib.robotparser.RobotFileParser")
    def test_robots_error_allows(self, mock_rp_cls):
        """robots.txt 확인 실패 시 허용."""
        from rag_pipeline.tools.scraper import _check_robots_txt

        mock_rp_cls.return_value.read.side_effect = Exception("timeout")

        assert _check_robots_txt("https://example.com/page") is True


class TestSimpleHTMLTextExtractor:
    """HTML 텍스트 추출기 테스트."""

    def test_extracts_text(self):
        """HTML에서 텍스트 추출."""
        from rag_pipeline.tools.scraper import _SimpleHTMLTextExtractor

        extractor = _SimpleHTMLTextExtractor()
        extractor.feed("<html><head><title>Title</title></head><body><p>Hello</p><p>World</p></body></html>")

        assert "Hello" in extractor.text
        assert "World" in extractor.text
        assert extractor.title == "Title"

    def test_skips_script_style(self):
        """script, style 태그 내용 제외."""
        from rag_pipeline.tools.scraper import _SimpleHTMLTextExtractor

        extractor = _SimpleHTMLTextExtractor()
        extractor.feed("<html><body><script>var x=1;</script><style>.a{}</style><p>Content</p></body></html>")

        assert "var x" not in extractor.text
        assert ".a{}" not in extractor.text
        assert "Content" in extractor.text
