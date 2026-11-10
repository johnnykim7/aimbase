import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { chatFeedbackApi } from "../api/chatFeedback";
import type { ChatFeedbackFilter } from "../types/chatFeedback";

export const useChatFeedback = (filter: ChatFeedbackFilter) =>
  useQuery({
    queryKey: ["chat-feedback", filter],
    queryFn: () =>
      chatFeedbackApi.list(filter).then((r) => ({
        rows: r.data.data ?? [],
        pagination: r.data.pagination,
      })),
    placeholderData: keepPreviousData,
    retry: false,
  });
