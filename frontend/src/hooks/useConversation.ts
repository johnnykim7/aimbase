import { useQuery } from "@tanstack/react-query";
import { chatApi, type ConversationBundle } from "../api/chat";

/**
 * CR-045: session_id로 세션 메타 + 메시지 히스토리 조회.
 *
 * sessionStorage에 `chat:<sid>:init`가 있으면 NewChatModal에서 방금 생성한
 * 신규 세션 → 첫 메시지 전송 전까지 BE에 존재하지 않음 → API 호출 생략하여
 * 브라우저 콘솔의 404 에러 로그 방지.
 */
export const useConversation = (sessionId: string | undefined) => {
  const isNewSession = !!(
    sessionId && typeof sessionStorage !== "undefined" &&
    sessionStorage.getItem(`chat:${sessionId}:init`) !== null
  );

  return useQuery<ConversationBundle | null>({
    queryKey: ["chat", "conversation", sessionId],
    queryFn: async () => {
      if (!sessionId) return null;
      const r = await chatApi.getConversation(sessionId);
      // chat.ts validateStatus에서 404를 허용 → 신규 세션(첫 메시지 전) 대응
      if (r.status === 404) return null;
      return r.data.data ?? null;
    },
    enabled: !!sessionId && !isNewSession,
    retry: false,
  });
};
