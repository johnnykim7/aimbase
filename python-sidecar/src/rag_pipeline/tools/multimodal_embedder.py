"""PY-016: Multimodal Embedding.

CLIP 모델을 활용하여 텍스트와 이미지를 동일 벡터 공간에 임베딩한다.
텍스트-이미지 간 유사도 검색, 멀티모달 RAG에 활용.
"""

import base64
import io
import logging
from typing import Any

logger = logging.getLogger(__name__)

DEFAULT_CLIP_MODEL = "openai/clip-vit-base-patch32"


def embed_multimodal(
    items: list[dict[str, str]],
    model: str = "",
) -> dict[str, Any]:
    """멀티모달 임베딩: 텍스트/이미지를 CLIP 벡터로 변환.

    Args:
        items: [{type: "text"|"image", content: str}] 목록.
               text는 일반 문자열, image는 base64 인코딩 문자열.
        model: CLIP 모델 이름 (기본: openai/clip-vit-base-patch32)

    Returns:
        {embeddings: [[float]], model: str, dimensions: int, items_processed: int}
    """
    if not items:
        return {
            "embeddings": [],
            "model": model or DEFAULT_CLIP_MODEL,
            "dimensions": 0,
            "items_processed": 0,
        }

    model_name = model or DEFAULT_CLIP_MODEL

    try:
        from transformers import CLIPModel, CLIPProcessor
    except ImportError:
        return {
            "error": "transformers 라이브러리가 설치되지 않았습니다. "
                     "'pip install transformers' 후 재시도하세요.",
            "embeddings": [],
            "model": model_name,
            "dimensions": 0,
            "items_processed": 0,
        }

    try:
        from PIL import Image as PILImage
    except ImportError:
        return {
            "error": "Pillow 라이브러리가 설치되지 않았습니다. "
                     "'pip install Pillow' 후 재시도하세요.",
            "embeddings": [],
            "model": model_name,
            "dimensions": 0,
            "items_processed": 0,
        }

    try:
        import torch
    except ImportError:
        return {
            "error": "torch 라이브러리가 설치되지 않았습니다. "
                     "'pip install torch' 후 재시도하세요.",
            "embeddings": [],
            "model": model_name,
            "dimensions": 0,
            "items_processed": 0,
        }

    try:
        clip_model = CLIPModel.from_pretrained(model_name)
        processor = CLIPProcessor.from_pretrained(model_name)
    except Exception as e:
        logger.error("CLIP 모델 로딩 실패: %s", e)
        return {
            "error": f"CLIP 모델 로딩 실패: {e}",
            "embeddings": [],
            "model": model_name,
            "dimensions": 0,
            "items_processed": 0,
        }

    embeddings: list[list[float]] = []
    items_processed = 0
    dimensions = 0

    for item in items:
        item_type = item.get("type", "text")
        content = item.get("content", "")

        if not content:
            logger.warning("빈 content, 스킵")
            continue

        try:
            if item_type == "text":
                inputs = processor(text=[content], return_tensors="pt", padding=True)
                features = clip_model.get_text_features(**inputs)
            elif item_type == "image":
                image_bytes = base64.b64decode(content)
                image = PILImage.open(io.BytesIO(image_bytes)).convert("RGB")
                inputs = processor(images=image, return_tensors="pt")
                features = clip_model.get_image_features(**inputs)
            else:
                logger.warning("알 수 없는 type '%s', 스킵", item_type)
                continue

            # Normalize to unit vector
            normalized = torch.nn.functional.normalize(features, p=2, dim=-1)
            embedding = normalized.detach().cpu().numpy().tolist()[0]

            embeddings.append(embedding)
            dimensions = len(embedding)
            items_processed += 1

        except Exception as e:
            logger.error("항목 임베딩 실패 (type=%s): %s", item_type, e)
            continue

    return {
        "embeddings": embeddings,
        "model": model_name,
        "dimensions": dimensions,
        "items_processed": items_processed,
    }
