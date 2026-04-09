export type ChunkingStrategy = "semantic" | "fixed" | "recursive" | "contextual" | "parent_child";

export interface ChunkingConfig {
  strategy?: ChunkingStrategy;
  max_chunk_size?: number;
  overlap?: number;
  parent_chunk_size?: number;
  child_chunk_size?: number;
}

export type EmbeddingModelId = "BAAI/bge-m3" | "text-embedding-3-small" | "text-embedding-3-large";

export interface KnowledgeSource {
  id: string;
  name: string;
  type?: "file" | "url" | "database" | "s3" | "mcp";
  status?: "active" | "ready" | "idle" | "syncing" | "error" | "pending";
  lastSyncAt?: string;
  documentCount?: number;
  chunkCount?: number;
  config?: Record<string, unknown>;
  chunkingConfig?: ChunkingConfig;
  embeddingModel?: EmbeddingModelId;
  createdAt?: string;
}

export interface IngestionLog {
  id: string;
  sourceId: string;
  status: "success" | "failure" | "in_progress";
  documentCount?: number;
  chunkCount?: number;
  errorMessage?: string;
  startedAt?: string;
  completedAt?: string;
}

export interface SearchRequest {
  query: string;
  topK?: number;
  sourceId?: string;
}

export interface SearchResult {
  chunkId?: string;
  content: string;
  similarity?: number;
  score?: number;
  sourceId?: string;
  sourceName?: string;
  metadata?: Record<string, unknown>;
}
