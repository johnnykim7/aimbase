"""Database utilities for pgvector operations."""

import contextlib
import contextvars
import json
import re
import uuid
from datetime import datetime, timezone

import psycopg
from pgvector.psycopg import register_vector

from rag_pipeline.config import settings

# CR-142: 요청 스코프 테넌트 DB 바인딩.
# 사이드카는 단일 DB_NAME 에 고정되어 있어 테넌트가 섞인다(BIZ-003 위반).
# BE 가 tenant_db 인자로 넘긴 DB 명을 요청 단위로 바인딩해 get_connection() 이 사용한다.
# 미지정이면 기존 settings.DB_NAME 으로 폴백 → 기존 호출 경로/stateless 도구 무회귀.
_current_db: contextvars.ContextVar[str | None] = contextvars.ContextVar(
    "tenant_db", default=None
)

# DB 명은 사이드카가 조립하지 않고 BE 가 해석한 값(master 의 tenants.db_name)을 받는다.
# 신뢰경계가 인자로 넓어지므로 형식을 검증한다.
_DB_NAME_RE = re.compile(r"^aimbase_[A-Za-z0-9_-]+$")

# 테넌트 DB 가 아닌 것은 명시적으로 거부한다.
_FORBIDDEN_DB_NAMES = frozenset({"aimbase_master"})


def validate_db_name(db_name: str) -> str:
    """Validate a tenant database name handed in by the caller."""
    if not isinstance(db_name, str) or not _DB_NAME_RE.fullmatch(db_name):
        raise ValueError(f"illegal tenant db name: {db_name!r}")
    if db_name in _FORBIDDEN_DB_NAMES:
        raise ValueError(f"forbidden tenant db name: {db_name!r}")
    return db_name


@contextlib.contextmanager
def tenant_db(db_name: str | None):
    """Bind the tenant database for the duration of a tool call.

    Falsy db_name keeps the legacy single-DB behaviour (settings.DB_NAME).
    """
    token = _current_db.set(validate_db_name(db_name) if db_name else None)
    try:
        yield
    finally:
        _current_db.reset(token)


def current_db_name() -> str:
    """Database name in effect for the current request."""
    return _current_db.get() or settings.DB_NAME


def get_connection() -> psycopg.Connection:
    """Create a new database connection with pgvector support."""
    conn = psycopg.connect(settings.db_url_for(current_db_name()), autocommit=True)
    register_vector(conn)
    return conn


def store_embeddings(
    source_id: str,
    chunks: list[dict],
    vectors: list[list[float]],
    document_id: str = "",
) -> int:
    """Store chunk embeddings into the embeddings table.

    Returns the number of rows inserted.
    """
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            rows = []
            for i, (chunk, vector) in enumerate(zip(chunks, vectors)):
                rows.append((
                    str(uuid.uuid4()),
                    source_id,
                    document_id or source_id,
                    i,
                    chunk["content"],
                    vector,
                    json.dumps(chunk.get("metadata", {})),
                    datetime.now(timezone.utc),
                ))

            cur.executemany(
                """
                INSERT INTO embeddings (id, source_id, document_id, chunk_index,
                                        content, embedding, metadata, created_at)
                VALUES (%s, %s, %s, %s, %s, %s::vector, %s::jsonb, %s)
                """,
                rows,
            )
        return len(rows)
    finally:
        conn.close()


def delete_embeddings_by_source(source_id: str) -> int:
    """Delete existing embeddings for a source. Returns deleted count."""
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "DELETE FROM embeddings WHERE source_id = %s", (source_id,)
            )
            return cur.rowcount
    finally:
        conn.close()


def vector_search(
    source_id: str,
    query_vector: list[float],
    top_k: int = 5,
) -> list[dict]:
    """Cosine similarity search using pgvector."""
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT content, metadata,
                       1 - (embedding <=> %s::vector) AS similarity
                FROM embeddings
                WHERE source_id = %s
                ORDER BY embedding <=> %s::vector
                LIMIT %s
                """,
                (query_vector, source_id, query_vector, top_k),
            )
            results = []
            for row in cur.fetchall():
                results.append({
                    "content": row[0],
                    "metadata": row[1] or {},
                    "vector_score": float(row[2]),
                })
            return results
    finally:
        conn.close()


def get_connection_api_key() -> str:
    """Get first available API key from connections table."""
    try:
        conn = get_connection()
        cur = conn.cursor()
        cur.execute("""
            SELECT config->>'apiKey' FROM connections
            WHERE config->>'apiKey' IS NOT NULL AND config->>'apiKey' != ''
            LIMIT 1
        """)
        row = cur.fetchone()
        cur.close()
        conn.close()
        return row[0] if row else ""
    except Exception:
        return ""


def get_openai_api_key() -> str:
    """Get OpenAI API key from connections table (type='OPENAI')."""
    try:
        conn = get_connection()
        cur = conn.cursor()
        cur.execute("""
            SELECT config->>'apiKey' FROM connections
            WHERE type = 'OPENAI'
              AND config->>'apiKey' IS NOT NULL AND config->>'apiKey' != ''
            LIMIT 1
        """)
        row = cur.fetchone()
        cur.close()
        conn.close()
        return row[0] if row else ""
    except Exception:
        return ""


def keyword_search_contents(source_id: str) -> list[dict]:
    """Fetch all chunk contents for a source (for BM25 indexing)."""
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT content, metadata FROM embeddings WHERE source_id = %s",
                (source_id,),
            )
            return [{"content": row[0], "metadata": row[1] or {}} for row in cur.fetchall()]
    finally:
        conn.close()
