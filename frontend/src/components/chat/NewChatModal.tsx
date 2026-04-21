import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { Modal } from "../common/Modal";
import { useWorkspaces } from "../../hooks/useWorkspaces";
import { useConnections } from "../../hooks/useConnections";
import { sessionsApi } from "../../api/sessions";

interface NewChatModalProps {
  open: boolean;
  onClose: () => void;
}

/**
 * CR-045 Phase 3-B: 새 대화 시작 모달.
 * 워크스페이스 + Connection 선택 후 client-side uuid로 session_id 발급하여
 * 빈 세션을 먼저 생성한 뒤 /chat/:sessionId로 이동.
 */
export const NewChatModal = ({ open, onClose }: NewChatModalProps) => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: workspaces = [], isLoading: wsLoading } = useWorkspaces();
  const { data: connections = [] } = useConnections();

  const [workspace, setWorkspace] = useState("");
  const [connectionId, setConnectionId] = useState("");
  const [model, setModel] = useState("claude-sonnet-4-5");
  const [title, setTitle] = useState("");
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!workspace && workspaces.length > 0) setWorkspace(workspaces[0].path);
  }, [workspaces, workspace]);

  useEffect(() => {
    if (!connectionId && connections.length > 0) setConnectionId(connections[0].id);
  }, [connections, connectionId]);

  const canCreate = !!workspace && !!connectionId && !!model && !creating;

  const handleCreate = async () => {
    const sessionId = crypto.randomUUID();
    const autoTitle = title.trim() || `새 대화 ${new Date().toLocaleString("ko-KR", { hour: "2-digit", minute: "2-digit" })}`;
    try {
      setError(null);
      setCreating(true);
      await sessionsApi.create({
        sessionId,
        title: autoTitle,
        workspaceRef: workspace,
        scopeType: "chat",
      });
      sessionStorage.setItem(
        `chat:${sessionId}:init`,
        JSON.stringify({ workspace, connectionId, model }),
      );
      queryClient.invalidateQueries({ queryKey: ["sessions"] });
      onClose();
      navigate(`/chat/${sessionId}`);
    } catch (e) {
      const message = e instanceof Error ? e.message : "채팅방을 만들지 못했습니다.";
      setError(
        /method not allowed|405/i.test(message)
          ? "백엔드가 최신 코드가 아닙니다. 서버를 재시작한 뒤 다시 시도하세요."
          : message,
      );
    } finally {
      setCreating(false);
    }
  };

  return (
    <Modal open={open} onClose={onClose} title="새 대화 시작" width={480}>
      <div className="space-y-4">
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">
            워크스페이스
          </label>
          <select
            className="w-full rounded border border-border bg-background px-3 py-2 text-sm"
            value={workspace}
            onChange={(e) => setWorkspace(e.target.value)}
            disabled={wsLoading}
          >
            {wsLoading && <option value="">로딩 중…</option>}
            {!wsLoading && workspaces.length === 0 && (
              <option value="">(사용 가능한 워크스페이스 없음)</option>
            )}
            {workspaces.map((w) => (
              <option key={w.path} value={w.path}>
                💼 {w.name}
              </option>
            ))}
          </select>
          {workspace && (
            <div className="mt-1 truncate text-xs text-muted-foreground" title={workspace}>
              {workspace}
            </div>
          )}
        </div>

        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">
            Connection
          </label>
          <select
            className="w-full rounded border border-border bg-background px-3 py-2 text-sm"
            value={connectionId}
            onChange={(e) => setConnectionId(e.target.value)}
          >
            {connections.length === 0 && <option value="">(Connection 없음)</option>}
            {connections.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} ({c.adapter})
              </option>
            ))}
          </select>
        </div>

        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">
            모델
          </label>
          <input
            type="text"
            className="w-full rounded border border-border bg-background px-3 py-2 text-sm"
            value={model}
            onChange={(e) => setModel(e.target.value)}
            placeholder="claude-sonnet-4-5"
          />
        </div>

        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">
            제목 <span className="text-muted-foreground/60">(선택)</span>
          </label>
          <input
            type="text"
            className="w-full rounded border border-border bg-background px-3 py-2 text-sm"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="미입력 시 자동 생성"
          />
        </div>

        <div className="flex justify-end gap-2 pt-2">
          {error && (
            <div className="mr-auto max-w-56 text-xs text-destructive">
              {error}
            </div>
          )}
          <button
            className="rounded border border-border bg-background px-4 py-2 text-sm hover:bg-muted"
            onClick={onClose}
          >
            취소
          </button>
          <button
            className="rounded bg-primary px-4 py-2 text-sm text-primary-foreground hover:opacity-90 disabled:opacity-40"
            disabled={!canCreate}
            onClick={handleCreate}
          >
            {creating ? "생성 중..." : "시작"}
          </button>
        </div>
      </div>
    </Modal>
  );
};
