"""Aimbase Safety - FastMCP Server.

MCP Server 3: PII Detection & Masking
Tools: detect_pii, mask_pii, validate_output
"""

import json
import logging

from fastmcp import FastMCP

from safety.config import settings

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

mcp = FastMCP(
    "Aimbase Safety",
    description="PII detection and masking tools with Korean language support (Presidio)",
)


@mcp.tool()
def detect_pii(
    text: str,
    language: str = "",
    entities: str = "[]",
    score_threshold: float = 0.0,
) -> str:
    """Detect PII entities in text.

    Supports Korean PII: 주민등록번호 (KR_RRN), 휴대폰번호 (KR_PHONE_NUMBER),
    계좌번호 (KR_BANK_ACCOUNT), plus standard Presidio entities (EMAIL, CREDIT_CARD, etc.).

    Args:
        text: Input text to analyze for PII
        language: Language code ("ko" or "en", default: "ko")
        entities: JSON array of entity types to detect (default: all)
        score_threshold: Minimum confidence score (0.0-1.0, default: 0.4)
    """
    from safety.engine import detect_pii as do_detect

    ent_list = json.loads(entities) if isinstance(entities, str) and entities.strip() not in ("", "[]") else None
    results = do_detect(text, language, ent_list, score_threshold)
    return json.dumps({"detections": results, "count": len(results)}, ensure_ascii=False)


@mcp.tool()
def mask_pii(
    text: str,
    language: str = "",
    entities: str = "[]",
    score_threshold: float = 0.0,
) -> str:
    """Detect and mask PII in text, returning anonymized output.

    Masking rules per entity type:
    - KR_RRN: ******-*******
    - KR_PHONE_NUMBER: 010-****-****
    - KR_BANK_ACCOUNT: fully masked
    - EMAIL_ADDRESS: ***@***.***
    - CREDIT_CARD: ****-****-****-****

    Args:
        text: Input text to mask PII in
        language: Language code ("ko" or "en", default: "ko")
        entities: JSON array of entity types to mask (default: all)
        score_threshold: Minimum confidence score (0.0-1.0, default: 0.4)
    """
    from safety.engine import mask_pii as do_mask

    ent_list = json.loads(entities) if isinstance(entities, str) and entities.strip() not in ("", "[]") else None
    result = do_mask(text, language, ent_list, score_threshold)
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
def validate_output(
    text: str,
    language: str = "",
    entities: str = "[]",
    score_threshold: float = 0.0,
) -> str:
    """Validate LLM output for PII leakage.

    Returns safety assessment: safe=true if no PII found,
    safe=false with violation details if PII detected.

    Args:
        text: LLM output text to validate
        language: Language code ("ko" or "en", default: "ko")
        entities: JSON array of entity types to check (default: all)
        score_threshold: Minimum confidence score (0.0-1.0, default: 0.4)
    """
    from safety.engine import validate_output as do_validate

    ent_list = json.loads(entities) if isinstance(entities, str) and entities.strip() not in ("", "[]") else None
    result = do_validate(text, language, ent_list, score_threshold)
    return json.dumps(result, ensure_ascii=False)


def main():
    """Start the Safety MCP server with SSE transport."""
    logger.info(
        "Starting Aimbase Safety MCP Server on %s:%d",
        settings.MCP_HOST,
        settings.MCP_PORT,
    )
    mcp.run(transport="sse", host=settings.MCP_HOST, port=settings.MCP_PORT)


if __name__ == "__main__":
    main()
