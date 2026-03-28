"""CR-019: Web Tools.

웹 검색/페치 MCP Tool.
- web_search: 외부 검색 API로 웹 검색
- web_fetch: URL에서 정제된 콘텐츠 추출
"""

import json
import logging
import os
import re
from typing import Any

logger = logging.getLogger(__name__)


def web_search(query: str, max_results: int = 5) -> dict[str, Any]:
    """웹 검색 결과 반환.

    Args:
        query: 검색 쿼리
        max_results: 최대 결과 수 (기본 5)

    Returns:
        {results: [{title, url, snippet}], count, success}
    """
    if not query.strip():
        return {"success": False, "error": "Empty query"}

    # SerpAPI 시도
    serpapi_key = os.getenv("SERPAPI_API_KEY", "")
    if serpapi_key:
        return _search_serpapi(query, max_results, serpapi_key)

    # DuckDuckGo fallback (라이브러리 필요 없음, HTML 파싱)
    return _search_duckduckgo(query, max_results)


def _search_serpapi(query: str, max_results: int, api_key: str) -> dict[str, Any]:
    """SerpAPI로 검색."""
    import urllib.request
    import urllib.parse

    params = urllib.parse.urlencode({
        "q": query, "api_key": api_key, "num": max_results, "engine": "google"
    })
    url = f"https://serpapi.com/search.json?{params}"

    try:
        with urllib.request.urlopen(url, timeout=10) as resp:
            data = json.loads(resp.read())

        results = []
        for item in data.get("organic_results", [])[:max_results]:
            results.append({
                "title": item.get("title", ""),
                "url": item.get("link", ""),
                "snippet": item.get("snippet", ""),
            })

        return {"results": results, "count": len(results), "source": "serpapi", "success": True}
    except Exception as e:
        return {"success": False, "error": f"SerpAPI failed: {e}"}


def _search_duckduckgo(query: str, max_results: int) -> dict[str, Any]:
    """DuckDuckGo Instant Answer API (제한적)."""
    import urllib.request
    import urllib.parse

    params = urllib.parse.urlencode({"q": query, "format": "json", "no_html": 1})
    url = f"https://api.duckduckgo.com/?{params}"

    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Aimbase/1.0"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read())

        results = []

        # Abstract
        if data.get("Abstract"):
            results.append({
                "title": data.get("Heading", query),
                "url": data.get("AbstractURL", ""),
                "snippet": data["Abstract"],
            })

        # Related Topics
        for topic in data.get("RelatedTopics", [])[:max_results]:
            if isinstance(topic, dict) and topic.get("Text"):
                results.append({
                    "title": topic.get("Text", "")[:80],
                    "url": topic.get("FirstURL", ""),
                    "snippet": topic.get("Text", ""),
                })

        return {"results": results[:max_results], "count": len(results), "source": "duckduckgo", "success": True}
    except Exception as e:
        return {"success": False, "error": f"DuckDuckGo search failed: {e}"}


def web_fetch(url: str, extract_mode: str = "text") -> dict[str, Any]:
    """URL에서 정제된 콘텐츠 추출.

    Args:
        url: 대상 URL
        extract_mode: text (정제 텍스트), html (원본 HTML), markdown (마크다운)

    Returns:
        {content, title, url, content_length, success}
    """
    if not url.strip():
        return {"success": False, "error": "Empty URL"}

    import urllib.request

    try:
        req = urllib.request.Request(url, headers={
            "User-Agent": "Mozilla/5.0 (compatible; Aimbase/1.0)"
        })
        with urllib.request.urlopen(req, timeout=15) as resp:
            raw_html = resp.read().decode("utf-8", errors="replace")

        title = _extract_title(raw_html)

        if extract_mode == "html":
            content = raw_html
        elif extract_mode == "markdown":
            content = _html_to_markdown(raw_html)
        else:
            content = _html_to_text(raw_html)

        logger.info("web_fetch: %s (%d chars)", url, len(content))

        return {
            "content": content,
            "title": title,
            "url": url,
            "content_length": len(content),
            "success": True,
        }

    except Exception as e:
        logger.error("web_fetch failed: %s", e)
        return {"success": False, "error": str(e)}


def _extract_title(html: str) -> str:
    m = re.search(r"<title[^>]*>(.*?)</title>", html, re.IGNORECASE | re.DOTALL)
    return m.group(1).strip() if m else ""


def _html_to_text(html: str) -> str:
    """HTML → 정제 텍스트. 광고/네비/스크립트 제거."""
    # script, style, nav, footer, header 제거
    for tag in ["script", "style", "nav", "footer", "header", "aside"]:
        html = re.sub(rf"<{tag}[^>]*>.*?</{tag}>", "", html, flags=re.IGNORECASE | re.DOTALL)

    # 태그 제거
    text = re.sub(r"<[^>]+>", " ", html)
    # 엔티티
    text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    # 연속 공백 정리
    text = re.sub(r"\s+", " ", text).strip()
    # 줄바꿈 정리
    lines = [line.strip() for line in text.split(". ") if line.strip()]
    return ".\n".join(lines)


def _html_to_markdown(html: str) -> str:
    """HTML → 간단한 마크다운 변환."""
    # 주요 태그 변환
    md = html
    for tag in ["script", "style", "nav", "footer", "header", "aside"]:
        md = re.sub(rf"<{tag}[^>]*>.*?</{tag}>", "", md, flags=re.IGNORECASE | re.DOTALL)

    md = re.sub(r"<h1[^>]*>(.*?)</h1>", r"\n# \1\n", md, flags=re.IGNORECASE | re.DOTALL)
    md = re.sub(r"<h2[^>]*>(.*?)</h2>", r"\n## \1\n", md, flags=re.IGNORECASE | re.DOTALL)
    md = re.sub(r"<h3[^>]*>(.*?)</h3>", r"\n### \1\n", md, flags=re.IGNORECASE | re.DOTALL)
    md = re.sub(r"<p[^>]*>(.*?)</p>", r"\1\n\n", md, flags=re.IGNORECASE | re.DOTALL)
    md = re.sub(r"<li[^>]*>(.*?)</li>", r"- \1\n", md, flags=re.IGNORECASE | re.DOTALL)
    md = re.sub(r"<br\s*/?>", "\n", md, flags=re.IGNORECASE)
    md = re.sub(r"<a[^>]*href=['\"]([^'\"]+)['\"][^>]*>(.*?)</a>", r"[\2](\1)", md, flags=re.IGNORECASE | re.DOTALL)
    md = re.sub(r"<strong[^>]*>(.*?)</strong>", r"**\1**", md, flags=re.IGNORECASE | re.DOTALL)
    md = re.sub(r"<b[^>]*>(.*?)</b>", r"**\1**", md, flags=re.IGNORECASE | re.DOTALL)
    md = re.sub(r"<em[^>]*>(.*?)</em>", r"*\1*", md, flags=re.IGNORECASE | re.DOTALL)

    # 나머지 태그 제거
    md = re.sub(r"<[^>]+>", "", md)
    md = md.replace("&nbsp;", " ").replace("&amp;", "&")
    md = re.sub(r"\n{3,}", "\n\n", md)
    return md.strip()
