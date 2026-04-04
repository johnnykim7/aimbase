import { useState } from "react";
import { cn } from "@/lib/utils";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { Modal } from "../components/common/Modal";
import { FormField, inputStyle, selectStyle } from "../components/common/FormField";
import { EmptyState } from "../components/common/EmptyState";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { Page } from "../components/layout/Page";
import { Wrench } from "lucide-react";
import {
  useMCPServers,
  useCreateMCPServer,
  useDeleteMCPServer,
  useDiscoverMCPTools,
  useDisconnectMCPServer,
} from "../hooks/useMCPServers";
import type { MCPServer, MCPServerRequest } from "../types/mcp";

function statusColor(s?: string): "success" | "danger" | "muted" {
  if (s === "connected") return "success";
  if (s === "error") return "danger";
  return "muted";
}

export default function MCPServers() {
  const { data: servers = [], isLoading } = useMCPServers();
  const createServer = useCreateMCPServer();
  const deleteServer = useDeleteMCPServer();
  const discoverTools = useDiscoverMCPTools();
  const disconnectServer = useDisconnectMCPServer();

  const [showModal, setShowModal] = useState(false);
  const [form, setForm] = useState<Partial<MCPServerRequest>>({
    transport: "http",
    autoStart: true,
  });

  const handleSave = () => {
    if (!form.id || !form.name || !form.transport) return;
    const url = (form.config as Record<string, string> | undefined)?.url ?? "";
    const req: MCPServerRequest = {
      id: form.id,
      name: form.name,
      transport: form.transport,
      config: { url },
      autoStart: form.autoStart ?? true,
    };
    createServer.mutate(req, { onSuccess: () => setShowModal(false) });
  };

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <Page
      actions={<ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>MCP 서버 등록</ActionButton>}
    >

      {servers.length === 0 ? (
        <EmptyState
          icon={<Wrench className="size-6" />}
          title="등록된 MCP 서버가 없습니다"
          description="MCP 서버를 등록하면 LLM이 외부 도구를 호출할 수 있습니다"
          action={<ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>MCP 서버 등록</ActionButton>}
        />
      ) : (
        <div className="flex flex-col gap-4">
          {servers.map((server: MCPServer) => (
            <div
              key={server.id}
              className={cn(
                "bg-card border border-border rounded-xl p-5 border-l-[3px]",
                server.status === "connected" ? "border-l-success" : "border-l-border"
              )}
            >
              <div className="flex justify-between items-start mb-3">
                <div className="flex gap-3 items-center">
                  <span className="text-2xl">🔧</span>
                  <div>
                    <div className="text-[15px] font-semibold text-foreground mb-1">{server.name}</div>
                    <div className="text-xs font-mono text-muted-foreground/60">
                      {(server.config as Record<string, string> | undefined)?.url ??
                        (server.config as Record<string, string> | undefined)?.command ??
                        server.name ?? server.id}
                    </div>
                  </div>
                </div>
                <div className="flex gap-2 items-center">
                  <Badge color={statusColor(server.status)} pulse={server.status === "connected"}>
                    {server.status === "connected" ? "연결됨" : server.status ?? "—"}
                  </Badge>
                  <Badge color="muted">{server.transport?.toUpperCase()}</Badge>
                </div>
              </div>

              {/* Tool chips */}
              {(server.toolsCache ?? server.discoveredTools) && (server.toolsCache ?? server.discoveredTools)!.length > 0 && (
                <div className="flex flex-wrap gap-1.5 mb-3">
                  {(server.toolsCache ?? server.discoveredTools)!.map((tool) => (
                    <span
                      key={tool.name}
                      className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-md text-[11px] font-mono bg-primary/10 text-primary border border-primary/20"
                    >
                      🔧 {tool.name}
                    </span>
                  ))}
                </div>
              )}

              {server.toolCount != null && !server.toolsCache && !server.discoveredTools && (
                <div className="text-xs font-mono text-muted-foreground/60 mb-3">
                  {server.toolCount}개 도구 등록됨
                </div>
              )}

              <div className="flex gap-1.5">
                <ActionButton variant="ghost" small icon="🔍" disabled={discoverTools.isPending} onClick={() => discoverTools.mutate(server.id)}>도구 탐색</ActionButton>
                <ActionButton variant="ghost" small onClick={() => disconnectServer.mutate(server.id)}>연결 해제</ActionButton>
                <ActionButton variant="danger" small onClick={() => deleteServer.mutate(server.id)}>삭제</ActionButton>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Register Modal */}
      <Modal open={showModal} onClose={() => setShowModal(false)} title="MCP 서버 등록">
        <FormField label="서버 ID">
          <input style={inputStyle} placeholder="my-mcp-server" value={form.id ?? ""} onChange={(e) => setForm((p) => ({ ...p, id: e.target.value }))} />
        </FormField>
        <FormField label="서버 이름">
          <input style={inputStyle} placeholder="주문 서비스 MCP" value={form.name ?? ""} onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))} />
        </FormField>
        <FormField label="Transport">
          <select style={selectStyle} value={form.transport ?? "http"} onChange={(e) => setForm((p) => ({ ...p, transport: e.target.value as MCPServerRequest["transport"] }))}>
            <option value="http">HTTP</option>
            <option value="stdio">stdio</option>
            <option value="sse">SSE</option>
          </select>
        </FormField>
        <FormField label={form.transport === "stdio" ? "실행 명령어" : "서버 URL"}>
          <input
            style={inputStyle}
            placeholder={form.transport === "stdio" ? "node /path/to/server.js" : "http://localhost:3001/mcp"}
            value={(form.config as Record<string, string> | undefined)?.url ?? ""}
            onChange={(e) => setForm((p) => ({ ...p, config: { url: e.target.value } }))}
          />
        </FormField>
        <FormField label="자동 시작">
          <label className="flex items-center gap-2 cursor-pointer">
            <input
              type="checkbox"
              checked={form.autoStart ?? true}
              onChange={(e) => setForm((p) => ({ ...p, autoStart: e.target.checked }))}
              className="accent-primary"
            />
            <span className="text-[13px] text-muted-foreground">서버 시작 시 자동 연결</span>
          </label>
        </FormField>
        <div className="flex gap-2 justify-end mt-2">
          <ActionButton variant="ghost" onClick={() => setShowModal(false)}>취소</ActionButton>
          <ActionButton variant="primary" icon="💾" disabled={createServer.isPending} onClick={handleSave}>등록</ActionButton>
        </div>
      </Modal>
    </Page>
  );
}
