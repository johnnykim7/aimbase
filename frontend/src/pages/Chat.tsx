import { useParams } from "react-router-dom";
import { useConversation } from "../hooks/useConversation";
import { ChatSidebar } from "../components/chat/ChatSidebar";
import { ChatHeader } from "../components/chat/ChatHeader";
import { ChatInput } from "../components/chat/ChatInput";

/**
 * CR-045: 대화형 채팅 페이지.
 * Phase 3-A: 라우트/레이아웃.
 * Phase 3-B (현재): ChatSidebar + NewChatModal + ChatHeader + ChatInput 배선.
 * Phase 4: useChatStream 훅 + 블록 렌더 (현재 입력창은 disabled).
 */
export default function Chat() {
  const { sessionId } = useParams<{ sessionId?: string }>();
  const { data: conversation, isLoading } = useConversation(sessionId);

  return (
    <div className="flex h-full">
      <ChatSidebar />

      <section className="flex flex-1 flex-col">
        <ChatHeader
          sessionId={sessionId}
          workspaceRef={conversation?.session.workspaceRef}
        />

        <div className="flex-1 overflow-y-auto p-4">
          {isLoading && (
            <div className="text-sm text-muted-foreground">로딩 중…</div>
          )}
          {!sessionId && (
            <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
              좌측 <b className="mx-1">+ 새 대화</b> 버튼으로 시작하세요
            </div>
          )}
          {sessionId && conversation && conversation.messages.length === 0 && (
            <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
              메시지 없음 — 입력창 활성화는 Phase 4 예정
            </div>
          )}
          {sessionId && conversation && conversation.messages.length > 0 && (
            <div className="space-y-3">
              {conversation.messages.map((m) => (
                <div key={m.id} className="rounded border border-border p-3 text-sm">
                  <div className="mb-1 text-xs font-medium text-muted-foreground">{m.role}</div>
                  <div className="whitespace-pre-wrap">{m.content}</div>
                </div>
              ))}
            </div>
          )}
        </div>

        <ChatInput disabled placeholder="메시지 입력 (Phase 4에서 활성화)" />
      </section>
    </div>
  );
}
