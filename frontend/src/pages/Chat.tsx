import { useEffect, useMemo } from "react";
import { useParams } from "react-router-dom";
import { useConversation } from "../hooks/useConversation";
import { useChatStream, type StreamMessage } from "../hooks/useChatStream";
import { ChatSidebar } from "../components/chat/ChatSidebar";
import { ChatHeader } from "../components/chat/ChatHeader";
import { ChatInput } from "../components/chat/ChatInput";
import { MessageList } from "../components/chat/MessageList";

interface SessionInit {
  workspace: string;
  connectionId: string;
  model: string;
}

function loadInit(sessionId: string | undefined): SessionInit | null {
  if (!sessionId) return null;
  try {
    const raw = sessionStorage.getItem(`chat:${sessionId}:init`);
    return raw ? (JSON.parse(raw) as SessionInit) : null;
  } catch {
    return null;
  }
}

/**
 * CR-045: 대화형 채팅 페이지.
 * Phase 4-A: useChatStream 배선, 텍스트/thinking 블록 렌더, ChatInput 활성화.
 * Phase 4-B 예정: tool_use_start/tool_result 블록.
 */
export default function Chat() {
  const { sessionId } = useParams<{ sessionId?: string }>();
  const { data: conversation, isLoading } = useConversation(sessionId);

  // sessionStorage에 저장된 신규 세션 초기값 (NewChatModal에서 put)
  const init = useMemo(() => loadInit(sessionId), [sessionId]);

  // 기존 세션 메시지를 useChatStream 초기값으로 매핑
  const initialMessages: StreamMessage[] = useMemo(() => {
    if (!conversation) return [];
    return conversation.messages.map((m) => ({
      id: m.id,
      role: m.role === "user" ? "user" : "assistant",
      blocks: [{ kind: "text", text: m.content }],
    }));
  }, [conversation]);

  const { messages, isStreaming, error, send, reset } = useChatStream();

  // 세션 전환 시 메시지 초기화
  useEffect(() => {
    reset(initialMessages);
  }, [sessionId, initialMessages, reset]);

  const workspaceRef = conversation?.session.workspaceRef ?? init?.workspace;
  const connectionId = init?.connectionId;
  const model = init?.model ?? "claude-sonnet-4-5";

  const canSend = !!sessionId && !!connectionId && !isStreaming;

  const handleSend = (text: string) => {
    if (!canSend) return;
    send({
      sessionId: sessionId!,
      connectionId: connectionId!,
      model,
      workingDirectory: workspaceRef,
      userText: text,
    });
  };

  return (
    <div className="flex h-full">
      <ChatSidebar />

      <section className="flex flex-1 flex-col">
        <ChatHeader sessionId={sessionId} workspaceRef={workspaceRef} model={model} />

        <div className="flex-1 overflow-y-auto p-4">
          {isLoading && <div className="text-sm text-muted-foreground">로딩 중…</div>}
          {!sessionId && (
            <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
              좌측 <b className="mx-1">+ 새 대화</b> 버튼으로 시작하세요
            </div>
          )}
          {sessionId && messages.length === 0 && !isLoading && (
            <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
              첫 메시지를 입력하세요
            </div>
          )}
          {sessionId && messages.length > 0 && (
            <MessageList messages={messages} isStreaming={isStreaming} />
          )}
          {error && (
            <div className="mt-3 rounded border border-destructive/50 bg-destructive/10 p-2 text-xs text-destructive">
              {error}
            </div>
          )}
        </div>

        <ChatInput
          disabled={!canSend}
          onSend={canSend ? handleSend : undefined}
          placeholder={
            !sessionId
              ? "새 대화를 먼저 시작하세요"
              : !connectionId
              ? "이 세션은 새 창에서 열린 세션이 아닙니다 (초기값 없음)"
              : isStreaming
              ? "응답 중…"
              : "메시지 입력 (Cmd+Enter 전송)"
          }
        />
      </section>
    </div>
  );
}
