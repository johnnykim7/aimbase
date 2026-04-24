import { useState } from "react";
import { NavLink, useNavigate, useParams } from "react-router-dom";
import { Plus, MessageCircle, Trash2, History } from "lucide-react";
import { cn } from "@/lib/utils";
import { useSessions, useDeleteSession } from "../../hooks/useSessions";
import { NewChatModal } from "./NewChatModal";
import { sessionsApi } from "../../api/sessions";
import { CompactBoundaryDivider } from "./CompactBoundaryDivider";
import type { ResumeResponse } from "../../api/sessions";

/**
 * CR-045 Phase 3-B: 좌측 사이드바.
 * 기존 세션 목록(useSessions) + [+ 새 대화] 버튼 → NewChatModal.
 */
export const ChatSidebar = () => {
  const [modalOpen, setModalOpen] = useState(false);
  const [confirmId, setConfirmId] = useState<string | null>(null);
  const [resumePreview, setResumePreview] = useState<ResumeResponse | null>(null);
  const [resumeLoading, setResumeLoading] = useState<string | null>(null);
  const { data: sessions = [], isLoading } = useSessions();
  const deleteMutation = useDeleteSession();
  const navigate = useNavigate();
  const { sessionId: currentId } = useParams<{ sessionId?: string }>();

  const handleResume = async (sessionId: string) => {
    setResumeLoading(sessionId);
    try {
      const res = await sessionsApi.resume(sessionId);
      setResumePreview(res.data.data ?? null);
    } catch (e) {
      // 404(TTL 초과 / 없음), 403, 409 등
      alert(`세션 재개 불가: ${(e as Error).message}`);
    } finally {
      setResumeLoading(null);
    }
  };

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
                aria-label="세션 재개 (압축 경계 이후 메시지 복원)"
                title="재개"
                className="absolute right-8 top-1/2 -translate-y-1/2 rounded p-1 text-muted-foreground opacity-0 hover:bg-accent hover:text-foreground group-hover:opacity-100"
                onClick={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  handleResume(s.sessionId);
                }}
                disabled={resumeLoading === s.sessionId}
              >
                <History className="h-3.5 w-3.5" />
              </button>
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

      {resumePreview && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
          onClick={() => setResumePreview(null)}
        >
          <div
            className="w-[640px] max-h-[80vh] overflow-auto rounded-lg border border-border bg-background p-4 shadow-lg"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="mb-2 text-sm font-semibold">
              세션 재개 — {resumePreview.session_id}
            </h3>
            <div className="text-[11px] text-muted-foreground mb-3">
              resumed_at: {resumePreview.resumed_at} · 복원된 메시지{" "}
              {resumePreview.messages.length}건
            </div>
            {resumePreview.boundary && (
              <CompactBoundaryDivider info={resumePreview.boundary} />
            )}
            <div className="space-y-2">
              {resumePreview.messages.slice(0, 20).map((m) => (
                <div
                  key={m.id}
                  className="rounded border border-border p-2 text-xs"
                >
                  <div className="text-[10px] text-muted-foreground mb-1">
                    {m.role} · {m.message_type} ·{" "}
                    {new Date(m.created_at).toLocaleString()}
                  </div>
                  {m.message_type === "COMPACT_BOUNDARY" && m.boundary_meta ? (
                    <CompactBoundaryDivider info={m.boundary_meta} />
                  ) : (
                    <div className="whitespace-pre-wrap">
                      {m.content.slice(0, 400)}
                      {m.content.length > 400 ? "…" : ""}
                    </div>
                  )}
                </div>
              ))}
              {resumePreview.messages.length > 20 && (
                <div className="text-[11px] text-muted-foreground text-center">
                  …외 {resumePreview.messages.length - 20}건 (대화방 열기 시 전체 표시)
                </div>
              )}
            </div>
            <div className="flex justify-end gap-2 mt-4">
              <button
                className="rounded border border-border px-3 py-1.5 text-xs hover:bg-muted"
                onClick={() => setResumePreview(null)}
              >
                닫기
              </button>
              <button
                className="rounded bg-primary px-3 py-1.5 text-xs text-primary-foreground hover:opacity-90"
                onClick={() => {
                  const sid = resumePreview.session_id;
                  setResumePreview(null);
                  navigate(`/chat/${sid}`);
                }}
              >
                대화방 열기
              </button>
            </div>
          </div>
        </div>
      )}

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
