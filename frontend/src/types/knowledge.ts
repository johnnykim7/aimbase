export interface KnowledgeSource {
  id: string;
  name: string;
  type?: "file" | "url" | "database" | "s3" | "mcp";
  status?: "active" | "syncing" | "error" | "pending";
  lastSyncAt?: string;
  documentCount?: number;
  chunkCount?: number;
  config?: Record<string, unknown>;
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
  sourceIds?: string[];
}

export interface SearchResult {
  chunkId?: string;
  content: string;
  similarity?: number;
  sourceId?: string;
  sourceName?: string;
  metadata?: Record<string, unknown>;
}
