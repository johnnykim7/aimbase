"""Database utilities for pgvector operations."""

import uuid
from datetime import datetime, timezone

import psycopg
from pgvector.psycopg import register_vector

from rag_pipeline.config import settings


def get_connection() -> psycopg.Connection:
    """Create a new database connection with pgvector support."""
    conn = psycopg.connect(settings.db_url, autocommit=True)
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
                    chunk.get("metadata", {}),
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
