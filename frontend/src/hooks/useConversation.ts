import { useQuery } from "@tanstack/react-query";
import { chatApi, type ConversationBundle } from "../api/chat";

/**
 * CR-045: session_id로 세션 메타 + 메시지 히스토리 조회.
 * 신규 세션은 BE에 없으므로 404가 정상. null 반환하여 호출자가 빈 상태로 렌더.
 */
export const useConversation = (sessionId: string | undefined) =>
  useQuery<ConversationBundle | null>({
    queryKey: ["chat", "conversation", sessionId],
    queryFn: async () => {
      if (!sessionId) return null;
      const r = await chatApi.getConversation(sessionId);
      // chat.ts validateStatus에서 404를 허용 → 신규 세션은 data=null
      if (r.status === 404) return null;
      return r.data.data ?? null;
    },
    enabled: !!sessionId,
    retry: false,
  });
