import { useQuery } from "@tanstack/react-query";
import { chatApi } from "../api/chat";

/**
 * CR-045: session_id로 세션 메타 + 메시지 히스토리 조회.
 */
export const useConversation = (sessionId: string | undefined) =>
  useQuery({
    queryKey: ["chat", "conversation", sessionId],
    queryFn: () =>
      sessionId ? chatApi.getConversation(sessionId).then((r) => r.data.data) : null,
    enabled: !!sessionId,
    retry: false,
  });
