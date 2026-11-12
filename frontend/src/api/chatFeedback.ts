import { apiClient } from "./client";
import type { ApiResponse } from "../types/api";
import type { ChatFeedbackFilter, ChatFeedbackRow } from "../types/chatFeedback";

export const chatFeedbackApi = {
  list: (filter: ChatFeedbackFilter) =>
    apiClient.get<ApiResponse<ChatFeedbackRow[]>>("/chat/feedback", { params: filter }),
};
