import { useParams } from "react-router-dom";
import { MessageCircle } from "lucide-react";
import { useConversation } from "../hooks/useConversation";

/**
 * CR-045: 대화형 채팅 페이지.
 * Phase 3-A: 기반 레이아웃만 (사이드바/헤더/대화창/입력창 placeholder).
 * Phase 3-B: 새 대화 모달 + 세션 사이드바 + 입력창 UI.
 * Phase 4: SSE 스트림 훅 + 메시지 블록 렌더.
 */
export default function Chat() {
  const { sessionId } = useParams<{ sessionId?: string }>();
  const { data: conversation, isLoading } = useConversation(sessionId);

  return (
    <div className="flex h-full">
      {/* 좌측 사이드바 placeholder — Phase 3-B에서 ChatSidebar로 교체 */}
      <aside className="w-72 shrink-0 border-r border-border bg-card">
        <div className="p-4 text-sm text-muted-foreground">
          세션 목록 (Phase 3-B 예정)
        </div>
      </aside>

      {/* 우측 대화창 */}
      <section className="flex flex-1 flex-col">
        {/* 헤더 placeholder — Phase 3-B에서 ChatHeader로 교체 */}
        <header className="flex items-center gap-2 border-b border-border px-4 py-3">
          <MessageCircle className="h-4 w-4 text-primary" />
          <span className="text-sm font-medium">
            {sessionId ? `세션: ${sessionId}` : "새 대화"}
          </span>
          {conversation?.session.workspaceRef && (
            <span className="ml-2 rounded bg-muted px-2 py-0.5 text-xs text-muted-foreground">
              💼 {conversation.session.workspaceRef}
            </span>
          )}
        </header>

        {/* 메시지 영역 placeholder */}
        <div className="flex-1 overflow-y-auto p-4">
          {isLoading && <div className="text-sm text-muted-foreground">로딩 중…</div>}
          {!sessionId && (
            <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
              새 대화를 시작하려면 좌측 <b className="mx-1">+ 새 대화</b> 버튼을 눌러주세요 (Phase 3-B 예정)
            </div>
          )}
          {sessionId && conversation && (
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

        {/* 입력창 placeholder — Phase 3-B에서 ChatInput으로 교체 */}
        <footer className="border-t border-border p-3">
          <input
            type="text"
            disabled
            placeholder="입력 (Phase 3-B 예정)"
            className="w-full rounded border border-border bg-muted px-3 py-2 text-sm"
          />
        </footer>
      </section>
    </div>
  );
}
