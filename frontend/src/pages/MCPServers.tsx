import { useState } from "react";
import { COLORS, FONTS } from "../theme";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { Modal } from "../components/common/Modal";
import { FormField, inputStyle, selectStyle } from "../components/common/FormField";
import { EmptyState } from "../components/common/EmptyState";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { PageHeader } from "../components/layout/PageHeader";
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
    <div>
      <PageHeader
        title="MCP / Tool"
        subtitle="Model Context Protocol 서버 관리"
        actions={
          <ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>
            MCP 서버 등록
          </ActionButton>
        }
      />

      {servers.length === 0 ? (
        <EmptyState
          icon="🔧"
          title="등록된 MCP 서버가 없습니다"
          description="MCP 서버를 등록하면 LLM이 외부 도구를 호출할 수 있습니다"
          action={
            <ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>
              MCP 서버 등록
            </ActionButton>
          }
        />
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
          {servers.map((server: MCPServer) => (
            <div
              key={server.id}
              style={{
                background: COLORS.surface,
                border: `1px solid ${COLORS.border}`,
                borderRadius: 12,
                padding: 20,
                borderLeft: `3px solid ${server.status === "connected" ? COLORS.success : COLORS.border}`,
              }}
            >
              <div
                style={{
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "flex-start",
                  marginBottom: 12,
                }}
              >
                <div style={{ display: "flex", gap: 12, alignItems: "center" }}>
                  <span style={{ fontSize: 24 }}>🔧</span>
                  <div>
                    <div
                      style={{
                        fontSize: 15,
                        fontWeight: 600,
                        fontFamily: FONTS.sans,
                        color: COLORS.text,
                        marginBottom: 4,
                      }}
                    >
                      {server.name}
                    </div>
                    <div
                      style={{
                        fontSize: 12,
                        fontFamily: FONTS.mono,
                        color: COLORS.textDim,
                      }}
                    >
                      {(server.config as Record<string, string> | undefined)?.url ??
                        (server.config as Record<string, string> | undefined)?.command ??
                        server.id}
                    </div>
                  </div>
                </div>
                <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                  <Badge color={statusColor(server.status)} pulse={server.status === "connected"}>
                    {server.status === "connected" ? "연결됨" : server.status ?? "—"}
                  </Badge>
                  <Badge color="muted">{server.transport?.toUpperCase()}</Badge>
                </div>
              </div>

              {/* Tool chips */}
              {(server.toolsCache ?? server.discoveredTools) && (server.toolsCache ?? server.discoveredTools)!.length > 0 && (
                <div style={{ display: "flex", flexWrap: "wrap", gap: 6, marginBottom: 12 }}>
                  {(server.toolsCache ?? server.discoveredTools)!.map((tool) => (
                    <span
                      key={tool.name}
                      style={{
                        display: "inline-flex",
                        alignItems: "center",
                        gap: 4,
                        padding: "3px 10px",
                        borderRadius: 6,
                        fontSize: 11,
                        fontFamily: FONTS.mono,
                        background: COLORS.accentDim + "30",
                        color: COLORS.accent,
                        border: `1px solid ${COLORS.accentDim}`,
                      }}
                    >
                      🔧 {tool.name}
                    </span>
                  ))}
                </div>
              )}

              {server.toolCount != null && !server.toolsCache && !server.discoveredTools && (
                <div
                  style={{
                    fontSize: 12,
                    fontFamily: FONTS.mono,
                    color: COLORS.textDim,
                    marginBottom: 12,
                  }}
                >
                  {server.toolCount}개 도구 등록됨
                </div>
              )}

              <div style={{ display: "flex", gap: 6 }}>
                <ActionButton
                  variant="ghost"
                  small
                  icon="🔍"
                  disabled={discoverTools.isPending}
                  onClick={() => discoverTools.mutate(server.id)}
                >
                  도구 탐색
                </ActionButton>
                <ActionButton
                  variant="ghost"
                  small
                  onClick={() => disconnectServer.mutate(server.id)}
                >
                  연결 해제
                </ActionButton>
                <ActionButton
                  variant="danger"
                  small
                  onClick={() => deleteServer.mutate(server.id)}
                >
                  삭제
                </ActionButton>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Register Modal */}
      <Modal open={showModal} onClose={() => setShowModal(false)} title="MCP 서버 등록">
        <FormField label="서버 ID">
          <input
            style={inputStyle}
            placeholder="my-mcp-server"
            value={form.id ?? ""}
            onChange={(e) => setForm((p) => ({ ...p, id: e.target.value }))}
          />
        </FormField>
        <FormField label="서버 이름">
          <input
            style={inputStyle}
            placeholder="주문 서비스 MCP"
            value={form.name ?? ""}
            onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))}
          />
        </FormField>
        <FormField label="Transport">
          <select
            style={selectStyle}
            value={form.transport ?? "http"}
            onChange={(e) => setForm((p) => ({ ...p, transport: e.target.value as MCPServerRequest["transport"] }))}
          >
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
            onChange={(e) =>
              setForm((p) => ({ ...p, config: { url: e.target.value } }))
            }
          />
        </FormField>
        <FormField label="자동 시작">
          <label style={{ display: "flex", alignItems: "center", gap: 8, cursor: "pointer" }}>
            <input
              type="checkbox"
              checked={form.autoStart ?? true}
              onChange={(e) => setForm((p) => ({ ...p, autoStart: e.target.checked }))}
              style={{ accentColor: COLORS.accent }}
            />
            <span style={{ fontSize: 13, fontFamily: FONTS.sans, color: COLORS.textMuted }}>
              서버 시작 시 자동 연결
            </span>
          </label>
        </FormField>
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 8 }}>
          <ActionButton variant="ghost" onClick={() => setShowModal(false)}>취소</ActionButton>
          <ActionButton
            variant="primary"
            icon="💾"
            disabled={createServer.isPending}
            onClick={handleSave}
          >
            등록
          </ActionButton>
        </div>
      </Modal>
    </div>
  );
}
