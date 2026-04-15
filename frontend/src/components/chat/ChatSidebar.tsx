import { useState } from "react";
import { NavLink } from "react-router-dom";
import { Plus, MessageCircle } from "lucide-react";
import { cn } from "@/lib/utils";
import { useSessions } from "../../hooks/useSessions";
import { NewChatModal } from "./NewChatModal";

/**
 * CR-045 Phase 3-B: 좌측 사이드바.
 * 기존 세션 목록(useSessions) + [+ 새 대화] 버튼 → NewChatModal.
 */
export const ChatSidebar = () => {
  const [modalOpen, setModalOpen] = useState(false);
  const { data: sessions = [], isLoading } = useSessions();

  return (
    <>
      <aside className="flex w-72 shrink-0 flex-col border-r border-border bg-card">
        <div className="border-b border-border p-3">
          <button
            className="flex w-full items-center justify-center gap-1.5 rounded bg-primary px-3 py-2 text-sm text-primary-foreground hover:opacity-90"
            onClick={() => setModalOpen(true)}
          >
            <Plus className="h-4 w-4" />
            새 대화
          </button>
        </div>

        <div className="flex-1 overflow-y-auto">
          {isLoading && (
            <div className="p-3 text-xs text-muted-foreground">로딩 중…</div>
          )}
          {!isLoading && sessions.length === 0 && (
            <div className="p-3 text-xs text-muted-foreground">
              아직 세션이 없습니다
            </div>
          )}
          {sessions.map((s) => (
            <NavLink
              key={s.sessionId}
              to={`/chat/${s.sessionId}`}
              className={({ isActive }) =>
                cn(
                  "flex items-start gap-2 border-b border-border/50 px-3 py-2 text-sm hover:bg-muted/50",
                  isActive && "bg-muted",
                )
              }
              title={s.sessionId}
            >
              <MessageCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-muted-foreground" />
              <div className="min-w-0 flex-1">
                <div className="truncate font-medium">
                  {s.title || s.sessionId}
                </div>
                {s.workspaceRef && (
                  <div className="mt-0.5 truncate text-[10px] text-muted-foreground">
                    💼 {s.workspaceRef.split("/").pop()}
                  </div>
                )}
              </div>
            </NavLink>
          ))}
        </div>
      </aside>

      <NewChatModal open={modalOpen} onClose={() => setModalOpen(false)} />
    </>
  );
};
