"""CR-019: Chart Renderer Tool.

데이터를 차트 이미지로 렌더링하는 MCP Tool.
matplotlib 기반, PNG base64 반환.
"""

import base64
import io
import json
import logging
from typing import Any

logger = logging.getLogger(__name__)


def render_chart(
    chart_type: str,
    data: str,
    title: str = "",
    options: str = "{}",
) -> dict[str, Any]:
    """데이터를 차트 이미지로 렌더링.

    Args:
        chart_type: bar, line, pie, scatter, hbar
        data: JSON 데이터. 형식:
              - bar/line/scatter: {"labels": [...], "values": [...]} 또는
                {"labels": [...], "datasets": [{"name": "A", "values": [...]}]}
              - pie: {"labels": [...], "values": [...]}
        title: 차트 제목
        options: JSON 옵션. {width, height, xlabel, ylabel, colors, legend}

    Returns:
        {image_base64, format, size_bytes, success}
    """
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        import matplotlib.font_manager as fm
    except ImportError:
        return {"success": False, "error": "matplotlib not installed"}

    # 한글 폰트 설정
    _setup_korean_font(plt, fm)

    try:
        chart_data = json.loads(data) if isinstance(data, str) else data
        opts = json.loads(options) if isinstance(options, str) else options
    except json.JSONDecodeError as e:
        return {"success": False, "error": f"Invalid JSON: {e}"}

    labels = chart_data.get("labels", [])
    values = chart_data.get("values", [])
    datasets = chart_data.get("datasets", [])

    width = opts.get("width", 10)
    height = opts.get("height", 6)
    colors = opts.get("colors", None)

    try:
        fig, ax = plt.subplots(figsize=(width, height))

        if chart_type == "bar":
            _draw_bar(ax, labels, values, datasets, colors)
        elif chart_type == "hbar":
            _draw_hbar(ax, labels, values, colors)
        elif chart_type == "line":
            _draw_line(ax, labels, values, datasets, colors)
        elif chart_type == "pie":
            _draw_pie(ax, labels, values, colors)
        elif chart_type == "scatter":
            _draw_scatter(ax, chart_data, datasets, colors)
        else:
            plt.close(fig)
            return {"success": False, "error": f"Unknown chart type: {chart_type}"}

        if title:
            ax.set_title(title, fontsize=14, fontweight="bold")
        if opts.get("xlabel"):
            ax.set_xlabel(opts["xlabel"])
        if opts.get("ylabel"):
            ax.set_ylabel(opts["ylabel"])
        if opts.get("legend", True) and (datasets or chart_type == "pie"):
            ax.legend()

        plt.tight_layout()

        buf = io.BytesIO()
        fig.savefig(buf, format="png", dpi=150, bbox_inches="tight")
        plt.close(fig)

        buf.seek(0)
        image_bytes = buf.read()
        image_base64 = base64.b64encode(image_bytes).decode("utf-8")

        logger.info("Chart rendered: %s (%d bytes)", chart_type, len(image_bytes))

        return {
            "image_base64": image_base64,
            "format": "png",
            "size_bytes": len(image_bytes),
            "chart_type": chart_type,
            "success": True,
        }

    except Exception as e:
        plt.close("all")
        logger.error("render_chart failed: %s", e)
        return {"success": False, "error": str(e)}


def _draw_bar(ax, labels, values, datasets, colors):
    import numpy as np
    if datasets:
        x = np.arange(len(labels))
        n = len(datasets)
        w = 0.8 / n
        for i, ds in enumerate(datasets):
            ax.bar(x + i * w - (n - 1) * w / 2, ds["values"], w, label=ds.get("name", f"Series {i+1}"))
        ax.set_xticks(x)
        ax.set_xticklabels(labels, rotation=45, ha="right")
    else:
        x = range(len(labels))
        ax.bar(x, values, color=colors)
        ax.set_xticks(x)
        ax.set_xticklabels(labels, rotation=45, ha="right")


def _draw_hbar(ax, labels, values, colors):
    ax.barh(labels, values, color=colors)


def _draw_line(ax, labels, values, datasets, colors):
    if datasets:
        for ds in datasets:
            ax.plot(labels, ds["values"], marker="o", label=ds.get("name", ""))
    else:
        ax.plot(labels, values, marker="o", color=colors[0] if colors else None)
    ax.tick_params(axis="x", rotation=45)


def _draw_pie(ax, labels, values, colors):
    ax.pie(values, labels=labels, colors=colors, autopct="%1.1f%%", startangle=90)
    ax.axis("equal")


def _draw_scatter(ax, data, datasets, colors):
    if datasets:
        for ds in datasets:
            ax.scatter(ds.get("x", []), ds.get("y", []), label=ds.get("name", ""), alpha=0.7)
    else:
        ax.scatter(data.get("x", []), data.get("y", []), color=colors[0] if colors else None, alpha=0.7)


_korean_font_configured = False


def _setup_korean_font(plt, fm):
    """한글 폰트 자동 설정. macOS/Linux/Windows 대응."""
    global _korean_font_configured
    if _korean_font_configured:
        return

    import platform
    system = platform.system()

    korean_fonts = []
    if system == "Darwin":  # macOS
        korean_fonts = [
            "/System/Library/Fonts/Supplemental/AppleGothic.ttf",
            "/System/Library/Fonts/AppleSDGothicNeo.ttc",
            "/Library/Fonts/NanumGothic.ttf",
        ]
    elif system == "Linux":
        korean_fonts = [
            "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
            "/usr/share/fonts/nanum/NanumGothic.ttf",
            "/usr/share/fonts/truetype/unfonts-core/UnDotum.ttf",
        ]
    else:  # Windows
        korean_fonts = [
            "C:/Windows/Fonts/malgun.ttf",
            "C:/Windows/Fonts/gulim.ttc",
        ]

    import os
    for font_path in korean_fonts:
        if os.path.exists(font_path):
            try:
                fm.fontManager.addfont(font_path)
                font_name = fm.FontProperties(fname=font_path).get_name()
                plt.rcParams["font.family"] = font_name
                plt.rcParams["axes.unicode_minus"] = False
                logger.info("Korean font configured: %s", font_name)
                _korean_font_configured = True
                return
            except Exception as e:
                logger.debug("Failed to load font %s: %s", font_path, e)

    # 폰트 못 찾으면 sans-serif 유지, 마이너스 부호만 수정
    plt.rcParams["axes.unicode_minus"] = False
    _korean_font_configured = True
    logger.warning("No Korean font found, Hangul characters may not render correctly")
