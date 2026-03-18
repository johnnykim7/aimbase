"""Tests for PY-016: Multimodal Embedding."""

import base64
import sys
from unittest.mock import MagicMock, patch

import pytest


def _reload_module():
    """모듈 캐시를 제거하고 새로 import."""
    sys.modules.pop("rag_pipeline.tools.multimodal_embedder", None)
    from rag_pipeline.tools.multimodal_embedder import embed_multimodal
    return embed_multimodal


class TestEmbedMultimodal:
    """멀티모달 임베딩 테스트."""

    def _setup_clip_mocks(self):
        """CLIP 모델/프로세서/torch mock 생성."""
        mock_clip_model_cls = MagicMock()
        mock_clip_proc_cls = MagicMock()
        mock_torch = MagicMock()

        mock_model = MagicMock()
        mock_processor = MagicMock()
        mock_clip_model_cls.from_pretrained.return_value = mock_model
        mock_clip_proc_cls.from_pretrained.return_value = mock_processor

        # 텍스트/이미지 features → normalize → tolist
        fake_features = MagicMock()
        mock_model.get_text_features.return_value = fake_features
        mock_model.get_image_features.return_value = fake_features

        normalized = MagicMock()
        normalized.detach.return_value.cpu.return_value.numpy.return_value.tolist.return_value = [
            [0.1] * 512
        ]
        mock_torch.nn.functional.normalize.return_value = normalized

        mock_pil_image = MagicMock()
        mock_pil_image.open.return_value.convert.return_value = MagicMock()

        return {
            "CLIPModel": mock_clip_model_cls,
            "CLIPProcessor": mock_clip_proc_cls,
            "torch": mock_torch,
            "PILImage": mock_pil_image,
            "model": mock_model,
            "processor": mock_processor,
        }

    def _patch_imports(self, mocks):
        """transformers, PIL, torch를 모킹하여 패치 컨텍스트 반환."""
        mock_transformers = MagicMock()
        mock_transformers.CLIPModel = mocks["CLIPModel"]
        mock_transformers.CLIPProcessor = mocks["CLIPProcessor"]

        mock_pil = MagicMock()
        mock_pil.Image = mocks["PILImage"]

        return patch.dict("sys.modules", {
            "transformers": mock_transformers,
            "PIL": mock_pil,
            "PIL.Image": mocks["PILImage"],
            "torch": mocks["torch"],
        })

    def test_text_embedding(self):
        """텍스트 항목 임베딩 테스트."""
        mocks = self._setup_clip_mocks()

        with self._patch_imports(mocks):
            embed_multimodal = _reload_module()

            items = [{"type": "text", "content": "hello world"}]
            result = embed_multimodal(items)

        assert result["items_processed"] == 1
        assert len(result["embeddings"]) == 1
        assert result["dimensions"] == 512
        assert result["model"] == "openai/clip-vit-base-patch32"

    def test_image_embedding(self):
        """이미지 항목 임베딩 테스트."""
        mocks = self._setup_clip_mocks()

        with self._patch_imports(mocks):
            embed_multimodal = _reload_module()

            b64_data = base64.b64encode(b"\x89PNG\r\n\x1a\n" + b"\x00" * 100).decode()
            items = [{"type": "image", "content": b64_data}]
            result = embed_multimodal(items)

        assert result["items_processed"] == 1
        assert len(result["embeddings"]) == 1

    def test_mixed_items(self):
        """텍스트+이미지 혼합 항목 테스트."""
        mocks = self._setup_clip_mocks()

        with self._patch_imports(mocks):
            embed_multimodal = _reload_module()

            items = [
                {"type": "text", "content": "hello"},
                {"type": "text", "content": "world"},
            ]
            result = embed_multimodal(items)

        assert result["items_processed"] == 2
        assert len(result["embeddings"]) == 2

    def test_empty_items(self):
        """빈 항목 리스트 테스트."""
        embed_multimodal = _reload_module()

        result = embed_multimodal([])

        assert result["embeddings"] == []
        assert result["items_processed"] == 0
        assert result["dimensions"] == 0

    def test_missing_transformers(self):
        """transformers 미설치 시 에러 반환."""
        with patch.dict("sys.modules", {"transformers": None}):
            embed_multimodal = _reload_module()

            items = [{"type": "text", "content": "test"}]
            result = embed_multimodal(items)

        assert "error" in result
        assert "transformers" in result["error"]
        assert result["embeddings"] == []

    def test_missing_pillow(self):
        """Pillow 미설치 시 에러 반환."""
        mock_transformers = MagicMock()

        with patch.dict("sys.modules", {
            "transformers": mock_transformers,
            "PIL": None,
            "PIL.Image": None,
        }):
            embed_multimodal = _reload_module()

            items = [{"type": "text", "content": "test"}]
            result = embed_multimodal(items)

        assert "error" in result
        assert "Pillow" in result["error"]

    def test_custom_model_name(self):
        """커스텀 모델 이름이 결과에 반영."""
        embed_multimodal = _reload_module()

        result = embed_multimodal([], model="custom/model")

        assert result["model"] == "custom/model"

    def test_unknown_type_skipped(self):
        """알 수 없는 type은 스킵."""
        mocks = self._setup_clip_mocks()

        with self._patch_imports(mocks):
            embed_multimodal = _reload_module()

            items = [{"type": "video", "content": "data"}]
            result = embed_multimodal(items)

        assert result["items_processed"] == 0
        assert result["embeddings"] == []

    def test_empty_content_skipped(self):
        """빈 content는 스킵."""
        mocks = self._setup_clip_mocks()

        with self._patch_imports(mocks):
            embed_multimodal = _reload_module()

            items = [{"type": "text", "content": ""}]
            result = embed_multimodal(items)

        assert result["items_processed"] == 0
