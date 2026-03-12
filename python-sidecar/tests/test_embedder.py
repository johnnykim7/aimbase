"""TC-PY-RAG-003: Local Embedding Tests."""

from rag_pipeline.tools.embedder import embed_single, embed_texts


class TestEmbedTexts:
    """TC-PY-RAG-003: Local embedding generation."""

    def test_embed_korean_texts(self):
        """Generate embeddings for Korean texts with KoSimCSE."""
        texts = ["안녕하세요", "반갑습니다"]
        result = embed_texts(texts, model="BM-K/KoSimCSE-roberta-multitask")

        assert len(result["embeddings"]) == 2
        assert result["dimensions"] == 768
        assert result["model"] == "BM-K/KoSimCSE-roberta-multitask"

        # Each embedding should be a list of floats
        for embedding in result["embeddings"]:
            assert len(embedding) == 768
            assert all(isinstance(v, float) for v in embedding)

    def test_embed_single(self):
        """Single text embedding returns vector."""
        vector = embed_single("테스트 문장입니다")
        assert len(vector) == 768
        assert all(isinstance(v, float) for v in vector)

    def test_similar_texts_have_high_similarity(self):
        """Semantically similar texts should have high cosine similarity."""
        import numpy as np

        texts = ["반품 정책은 무엇인가요?", "반품 절차를 알려주세요"]
        result = embed_texts(texts)

        v1 = np.array(result["embeddings"][0])
        v2 = np.array(result["embeddings"][1])
        similarity = np.dot(v1, v2)  # normalized → dot product = cosine similarity

        assert similarity > 0.5  # similar texts should have reasonable similarity
