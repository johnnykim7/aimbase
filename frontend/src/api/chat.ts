import { apiClient } from "./client";
import type { ApiResponse } from "../types/api";

export interface ChatMessageContent {
  type: "text" | "thinking" | "tool_use" | "tool_result";
  text?: string;
  id?: string;
  name?: string;
  input?: Record<string, unknown>;
  tool_use_id?: string;
  output?: string;
  is_error?: boolean;
}

export interface ChatCompletionRequest {
  model: string;
  session_id: string;
  connection_id?: string;
  connection_group_id?: string;
  messages: { role: string; content: string | ChatMessageContent[] }[];
  stream?: boolean;
  actions_enabled?: boolean;
  tool_filter?: { allowed_tools?: string[]; exclude_tools?: string[] };
  tool_choice?: string;
  response_format?: { type: string; schema_ref?: string; schema?: Record<string, unknown> };
  working_directory?: string;
}

export interface ChatCompletionResponse {
  id: string;
  model: string;
  session_id: string;
  content: ChatMessageContent[];
  actions_executed?: Record<string, unknown>[];
  usage: {
    input_tokens: number;
    output_tokens: number;
    cost_usd: number;
    cache_creation_input_tokens?: number;
    cache_read_input_tokens?: number;
  };
}

export interface ConversationMessageRow {
  id: string;
  sessionId: string;
  role: string;
  content: string;
  model?: string;
  tokens?: number;
  createdAt: string;
}

export interface ConversationSessionRow {
  sessionId: string;
  title?: string;
  workspaceRef?: string;
  messageCount?: number;
  createdAt: string;
  updatedAt: string;
}

export interface ConversationBundle {
  session: ConversationSessionRow;
  messages: ConversationMessageRow[];
}

export const chatApi = {
  /** 비스트리밍 완성 요청 */
  completions: (body: ChatCompletionRequest) =>
    apiClient.post<ApiResponse<ChatCompletionResponse>>("/chat/completions", {
      ...body,
      stream: false,
    }),

  /** 세션 + 메시지 번들 조회. 404(신규 세션)도 정상 처리. */
  getConversation: (sessionId: string) =>
    apiClient.get<ApiResponse<ConversationBundle>>(`/conversations/${sessionId}`, {
      validateStatus: (s) => (s >= 200 && s < 300) || s === 404,
    }),
};
