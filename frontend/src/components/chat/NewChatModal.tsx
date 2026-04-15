import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { Modal } from "../common/Modal";
import { useWorkspaces } from "../../hooks/useWorkspaces";
import { useConnections } from "../../hooks/useConnections";

interface NewChatModalProps {
  open: boolean;
  onClose: () => void;
}

/**
 * CR-045 Phase 3-B: 새 대화 시작 모달.
 * 워크스페이스 + Connection 선택 후 client-side uuid로 session_id 발급하여
 * /chat/:sessionId로 이동. 실제 세션/메타는 첫 메시지 전송 시 BE가 생성.
 */
export const NewChatModal = ({ open, onClose }: NewChatModalProps) => {
  const navigate = useNavigate();
  const { data: workspaces = [], isLoading: wsLoading } = useWorkspaces();
  const { data: connections = [] } = useConnections();

  const [workspace, setWorkspace] = useState("");
  const [connectionId, setConnectionId] = useState("");
  const [model, setModel] = useState("claude-sonnet-4-5");

  useEffect(() => {
    if (!workspace && workspaces.length > 0) setWorkspace(workspaces[0].path);
  }, [workspaces, workspace]);

  useEffect(() => {
    if (!connectionId && connections.length > 0) setConnectionId(connections[0].id);
  }, [connections, connectionId]);

  const canCreate = !!workspace && !!connectionId && !!model;

  const handleCreate = () => {
    const sessionId = crypto.randomUUID();
    // 세션 메타는 첫 메시지 전송 시 BE가 working_directory + connection_id를 받아 생성.
    // Phase 4에서 sessionStorage로 선택값 전달 → ChatConversation이 첫 전송 시 사용.
    sessionStorage.setItem(
      `chat:${sessionId}:init`,
      JSON.stringify({ workspace, connectionId, model }),
    );
    onClose();
    navigate(`/chat/${sessionId}`);
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

        <div className="flex justify-end gap-2 pt-2">
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
            시작
          </button>
        </div>
      </div>
    </Modal>
  );
};
