import { apiClient } from "./client";
import type { ApiResponse } from "../types/api";

export interface RagEvaluation {
  id: string;
  sourceId: string;
  evaluationType: string;
  metrics: Record<string, number>;
  config: Record<string, unknown>;
  testSet?: { question: string }[];
  mode?: string;
  sampleCount: number;
  status: "pending" | "running" | "completed" | "failed";
  errorMessage?: string;
  createdAt: string;
  completedAt?: string;
}

export interface RagQualityRequest {
  sourceId: string;
  testSet: { question: string; ground_truth?: string; expected_contexts?: string[] }[];
  config?: Record<string, unknown>;
  mode?: "fast" | "accurate";
}

export const evaluationApi = {
  runRagQuality: (data: RagQualityRequest) =>
    apiClient.post<ApiResponse<{ evaluation_id: string; status: string }>>(
      "/evaluations/rag-quality", data
    ),

  getRagQuality: (id: string) =>
    apiClient.get<ApiResponse<RagEvaluation>>(`/evaluations/rag-quality/${id}`),

  listRagQuality: (sourceId: string) =>
    apiClient.get<ApiResponse<RagEvaluation[]>>("/evaluations/rag-quality", {
      params: { source_id: sourceId },
    }),
};
