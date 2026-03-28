"""PY-023: Contextual Chunking MCP Tool (CR-011).

Anthropic Contextual Retrieval 기법 구현.
청크별로 LLM을 호출하여 문서 전체 맥락을 설명하는 접두사를 생성하고,
접두사 + 원본 청크를 결합하여 임베딩하면 검색 정확도 49% 향상.
"""

import hashlib
import logging
from typing import Any

from rag_pipeline.tools.chunker import chunk_document
from rag_pipeline.tools.embedder import embed_texts

logger = logging.getLogger(__name__)

CONTEXT_PROMPT_TEMPLATE = """<document>
{document}
</document>

위 문서에서 아래 청크가 위치합니다. 이 청크를 검색에서 정확히 찾을 수 있도록,
문서 전체 맥락에서 이 청크의 역할과 위치를 간결하게 설명하는 접두 문장(50~100자)을 생성해주세요.

<chunk>
{chunk}
</chunk>

접두 문장:"""


def contextual_chunk(
    content: str,
    document_context: str = "",
    chunking_config: dict[str, Any] | None = None,
    llm_caller: Any = None,
) -> dict[str, Any]:
    """문서를 청킹한 뒤, 각 청크에 LLM 생성 문맥 접두사를 부여.

    Args:
        content: 원본 문서 텍스트
        document_context: 문서 전체 요약 (없으면 content 앞 500자 사용)
        chunking_config: 청킹 설정 {strategy, max_chunk_size, overlap, ...}
        llm_caller: LLM 호출 콜백 (None이면 휴리스틱 접두사 생성)

    Returns:
        {chunks: [{content, context_prefix, content_hash, metadata}], success}
    """
    config = chunking_config or {}
    strategy = config.pop("strategy", "semantic")

    # 1단계: 기본 청킹
    raw_chunks = chunk_document(content, strategy, config)
    logger.info("Contextual chunking: %d raw chunks from %d chars", len(raw_chunks), len(content))

    # 문서 전체 맥락 (접두사 생성에 사용)
    doc_ctx = document_context or content[:2000]

    # 2단계: 각 청크에 context prefix 생성
    result_chunks = []
    for i, chunk in enumerate(raw_chunks):
        chunk_text = chunk["content"]
        content_hash = hashlib.sha256(chunk_text.encode()).hexdigest()

        if llm_caller is not None:
            # LLM을 통한 고품질 접두사 생성
            prompt = CONTEXT_PROMPT_TEMPLATE.format(document=doc_ctx, chunk=chunk_text)
            try:
                context_prefix = llm_caller(prompt)
            except Exception as e:
                logger.warning("LLM context prefix failed for chunk %d: %s", i, e)
                context_prefix = _heuristic_prefix(chunk_text, doc_ctx, i, len(raw_chunks))
        else:
            # 휴리스틱 접두사 (LLM 없을 때 fallback)
            context_prefix = _heuristic_prefix(chunk_text, doc_ctx, i, len(raw_chunks))

        result_chunks.append({
            "content": chunk_text,
            "context_prefix": context_prefix,
            "content_hash": content_hash,
            "chunk_index": i,
            "metadata": {
                **(chunk.get("metadata", {})),
                "contextual": True,
                "has_llm_prefix": llm_caller is not None,
            },
        })

    return {"chunks": result_chunks, "chunk_count": len(result_chunks), "success": True}


def contextual_embed(chunks: list[dict], model: str = "") -> list[list[float]]:
    """context_prefix + content를 결합하여 임베딩 벡터 생성.

    Args:
        chunks: contextual_chunk() 결과의 chunks 리스트
        model: 임베딩 모델명

    Returns:
        각 청크의 임베딩 벡터 리스트
    """
    texts = []
    for chunk in chunks:
        prefix = chunk.get("context_prefix", "")
        content = chunk["content"]
        combined = f"{prefix}\n{content}" if prefix else content
        texts.append(combined)

    result = embed_texts(texts, model)
    return result.get("embeddings", [])


def _heuristic_prefix(chunk_text: str, doc_context: str, index: int, total: int) -> str:
    """LLM 없이 휴리스틱으로 문맥 접두사 생성."""
    position = "앞부분" if index < total / 3 else ("중간부분" if index < total * 2 / 3 else "뒷부분")
    # 문서 첫 줄을 제목으로 활용
    first_line = doc_context.split("\n")[0][:80].strip()
    if first_line:
        return f"문서 '{first_line}'의 {position} ({index + 1}/{total}번째 청크)에 해당하는 내용입니다."
    return f"문서의 {position} ({index + 1}/{total}번째 청크)에 해당하는 내용입니다."
