import { useState } from "react";
import { NavLink, useNavigate, useParams } from "react-router-dom";
import { Plus, MessageCircle, Trash2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { useSessions, useDeleteSession } from "../../hooks/useSessions";
import { NewChatModal } from "./NewChatModal";

/**
 * CR-045 Phase 3-B: 좌측 사이드바.
 * 기존 세션 목록(useSessions) + [+ 새 대화] 버튼 → NewChatModal.
 */
export const ChatSidebar = () => {
  const [modalOpen, setModalOpen] = useState(false);
  const [confirmId, setConfirmId] = useState<string | null>(null);
  const { data: sessions = [], isLoading } = useSessions();
  const deleteMutation = useDeleteSession();
  const navigate = useNavigate();
  const { sessionId: currentId } = useParams<{ sessionId?: string }>();

  const handleConfirmDelete = async () => {
    if (!confirmId) return;
    const target = confirmId;
    setConfirmId(null);
    await deleteMutation.mutateAsync(target);
    if (currentId === target) navigate("/chat");
  };

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
            <div key={s.sessionId} className="group relative">
              <NavLink
                to={`/chat/${s.sessionId}`}
                className={({ isActive }) =>
                  cn(
                    "flex items-start gap-2 border-b border-border/50 px-3 py-2 pr-9 text-sm hover:bg-muted/50",
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
              <button
                type="button"
                aria-label="대화방 삭제"
                className="absolute right-2 top-1/2 -translate-y-1/2 rounded p-1 text-muted-foreground opacity-0 hover:bg-destructive/10 hover:text-destructive group-hover:opacity-100"
                onClick={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  setConfirmId(s.sessionId);
                }}
              >
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            </div>
          ))}
        </div>
      </aside>

      <NewChatModal open={modalOpen} onClose={() => setModalOpen(false)} />

      {confirmId && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
          onClick={() => setConfirmId(null)}
        >
          <div
            className="w-80 rounded-lg border border-border bg-background p-4 shadow-lg"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="mb-2 text-sm font-semibold">대화방 삭제</h3>
            <p className="mb-4 text-xs text-muted-foreground">
              이 대화방을 삭제하시겠습니까? Soft Delete 되므로 감사 로그는 보존됩니다.
            </p>
            <div className="flex justify-end gap-2">
              <button
                className="rounded border border-border px-3 py-1.5 text-xs hover:bg-muted"
                onClick={() => setConfirmId(null)}
              >
                취소
              </button>
              <button
                className="rounded bg-destructive px-3 py-1.5 text-xs text-destructive-foreground hover:opacity-90 disabled:opacity-40"
                onClick={handleConfirmDelete}
                disabled={deleteMutation.isPending}
              >
                {deleteMutation.isPending ? "삭제 중…" : "삭제"}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
};
