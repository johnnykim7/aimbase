import { useState } from "react";
import { COLORS, FONTS } from "../theme";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { Modal } from "../components/common/Modal";
import { FormField, inputStyle } from "../components/common/FormField";
import { EmptyState } from "../components/common/EmptyState";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { PageHeader } from "../components/layout/PageHeader";
import {
  useConnections,
  useCreateConnection,
  useUpdateConnection,
  useDeleteConnection,
  useTestConnection,
} from "../hooks/useConnections";
import type { Connection, ConnectionRequest } from "../types/connection";

const TYPE_ICONS: Record<string, string> = {
  database: "🗄️",
  messaging: "💬",
  llm: "🤖",
  realtime: "🔌",
  postgresql: "🐘",
  mysql: "🐬",
  slack: "💬",
  kakao: "💛",
  claude: "🤖",
  openai: "🧠",
  websocket: "🔌",
};

function getIcon(conn: Connection) {
  return (
    TYPE_ICONS[conn.adapter?.toLowerCase() ?? ""] ??
    TYPE_ICONS[conn.type?.toLowerCase() ?? ""] ??
    "🔗"
  );
}

function statusColor(s?: string): "success" | "warning" | "danger" | "muted" {
  if (s === "connected") return "success";
  if (s === "warning") return "warning";
  if (s === "error" || s === "disconnected") return "danger";
  return "muted";
}

const CONNECTION_TYPES = [
  { id: "database", icon: "🗄️", label: "Database" },
  { id: "messaging", icon: "💬", label: "Messaging" },
  { id: "llm", icon: "🤖", label: "LLM" },
  { id: "realtime", icon: "🔌", label: "Realtime" },
];

const ADAPTERS: Record<string, string[]> = {
  database: ["PostgreSQL", "MySQL", "MongoDB", "Redis"],
  messaging: ["Slack", "카카오톡", "Discord", "Webhook"],
  llm: ["Claude (Anthropic)", "OpenAI", "Ollama"],
  realtime: ["WebSocket", "SSE"],
};

const DB_FIELDS = ["연결 ID", "이름", "호스트", "포트", "데이터베이스", "사용자", "비밀번호"];

export default function Connections() {
  const { data: connections = [], isLoading } = useConnections();
  const createConnection = useCreateConnection();
  const updateConnection = useUpdateConnection();
  const deleteConnection = useDeleteConnection();
  const testConnection = useTestConnection();

  const [showModal, setShowModal] = useState(false);
  const [editingConn, setEditingConn] = useState<Connection | null>(null);
  const [selectedType, setSelectedType] = useState("database");
  const [selectedAdapter, setSelectedAdapter] = useState("PostgreSQL");
  const [testResults, setTestResults] = useState<Record<string, { ok: boolean; latencyMs: number }>>({});
  const [form, setForm] = useState<Record<string, string>>({});

  const handleTest = async (id: string) => {
    const result = await testConnection.mutateAsync(id);
    setTestResults((prev) => ({ ...prev, [id]: result }));
  };

  const openCreateModal = () => {
    setEditingConn(null);
    setForm({});
    setSelectedType("database");
    setSelectedAdapter("PostgreSQL");
    setShowModal(true);
  };

  const openEditModal = (conn: Connection) => {
    setEditingConn(conn);
    setSelectedType(conn.type ?? "llm");
    setSelectedAdapter(conn.adapter ?? "");
    const cfg = conn.config ?? {};
    setForm({
      "연결 ID": conn.id,
      "이름": conn.name ?? "",
      "호스트": (cfg.host as string) ?? "",
      "포트": (cfg.port as string) ?? "",
      "데이터베이스": (cfg.database as string) ?? "",
      "사용자": (cfg.username as string) ?? "",
      "비밀번호": (cfg.password as string) ?? "",
      "URL": (cfg.url as string) ?? "",
      "API Key": (cfg.apiKey as string) ?? "",
      "Token": (cfg.token as string) ?? "",
      "모델": (cfg.model as string) ?? "",
    });
    setShowModal(true);
  };

  const handleSave = () => {
    const config: Record<string, unknown> = {
      host: form["호스트"],
      port: form["포트"],
      database: form["데이터베이스"],
      username: form["사용자"],
      password: form["비밀번호"],
      url: form["URL"],
      apiKey: form["API Key"],
      token: form["Token"],
      model: form["모델"],
    };

    if (editingConn) {
      updateConnection.mutate(
        { id: editingConn.id, data: { id: editingConn.id, name: form["이름"] ?? editingConn.name, adapter: selectedAdapter, type: selectedType, config } },
        { onSuccess: () => { setShowModal(false); setEditingConn(null); } },
      );
    } else {
      const req: ConnectionRequest = {
        id: form["연결 ID"] ?? "",
        name: form["이름"] ?? form["연결 ID"] ?? "",
        adapter: selectedAdapter,
        type: selectedType,
        config,
      };
      createConnection.mutate(req, { onSuccess: () => setShowModal(false) });
    }
  };

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <div>
      <PageHeader
        title="연결 관리"
        actions={
          <ActionButton variant="primary" icon="+" onClick={openCreateModal}>
            새 연결
          </ActionButton>
        }
      />

      {connections.length === 0 ? (
        <EmptyState
          icon="🔌"
          title="연결된 시스템이 없습니다"
          description="DB, 메시징, LLM, 실시간 연결을 추가하세요"
          action={
            <ActionButton variant="primary" icon="+" onClick={openCreateModal}>
              새 연결 추가
            </ActionButton>
          }
        />
      ) : (
        <div
          style={{
            display: "grid",
            gridTemplateColumns: "repeat(auto-fill, minmax(340px, 1fr))",
            gap: 16,
          }}
        >
          {connections.map((conn: Connection) => {
            const testResult = testResults[conn.id];
            return (
              <div
                key={conn.id}
                style={{
                  background: COLORS.surface,
                  border: `1px solid ${COLORS.border}`,
                  borderRadius: 12,
                  padding: 20,
                  position: "relative",
                  borderLeft: `3px solid ${
                    conn.status === "connected" ? COLORS.success : COLORS.warning
                  }`,
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
                  <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
                    <span style={{ fontSize: 24 }}>{getIcon(conn)}</span>
                    <div>
                      <div
                        style={{
                          fontSize: 14,
                          fontWeight: 600,
                          fontFamily: FONTS.mono,
                          color: COLORS.text,
                        }}
                      >
                        {conn.id}
                      </div>
                      <div style={{ fontSize: 12, color: COLORS.textMuted }}>{conn.adapter}</div>
                    </div>
                  </div>
                  <Badge color={statusColor(conn.status)}>{conn.type}</Badge>
                </div>

                <div
                  style={{
                    fontSize: 12,
                    fontFamily: FONTS.mono,
                    color: COLORS.textDim,
                    marginBottom: 8,
                  }}
                >
                  {conn.name}
                </div>

                {testResult && (
                  <div
                    style={{
                      fontSize: 11,
                      fontFamily: FONTS.mono,
                      color: testResult.ok ? COLORS.success : COLORS.danger,
                      marginBottom: 8,
                    }}
                  >
                    {testResult.ok ? `✓ 연결 성공 (${testResult.latencyMs}ms)` : "✕ 연결 실패"}
                  </div>
                )}

                <div
                  style={{
                    display: "flex",
                    justifyContent: "space-between",
                    alignItems: "center",
                  }}
                >
                  <span style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textDim }}>
                    {conn.lastHealthCheckAt
                      ? `${new Date(conn.lastHealthCheckAt).toLocaleTimeString("ko-KR")} 확인`
                      : conn.status ?? "—"}
                  </span>
                  <div style={{ display: "flex", gap: 4 }}>
                    <ActionButton
                      variant="ghost"
                      small
                      onClick={() => openEditModal(conn)}
                    >
                      수정
                    </ActionButton>
                    <ActionButton
                      variant="ghost"
                      small
                      disabled={testConnection.isPending}
                      onClick={() => handleTest(conn.id)}
                    >
                      테스트
                    </ActionButton>
                    <ActionButton
                      variant="ghost"
                      small
                      onClick={() => deleteConnection.mutate(conn.id)}
                    >
                      삭제
                    </ActionButton>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* New Connection Modal */}
      <Modal open={showModal} onClose={() => { setShowModal(false); setEditingConn(null); }} title={editingConn ? "연결 수정" : "새 연결 추가"}>
        <FormField label="연결 유형">
          <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
            {CONNECTION_TYPES.map((t) => (
              <button
                key={t.id}
                onClick={() => {
                  setSelectedType(t.id);
                  setSelectedAdapter(ADAPTERS[t.id]?.[0] ?? "");
                }}
                style={{
                  flex: 1,
                  padding: "12px 8px",
                  borderRadius: 10,
                  border: `1px solid ${selectedType === t.id ? COLORS.accent : COLORS.border}`,
                  background:
                    selectedType === t.id ? COLORS.accentDim + "30" : COLORS.surfaceHover,
                  color: selectedType === t.id ? COLORS.accent : COLORS.textMuted,
                  cursor: "pointer",
                  textAlign: "center",
                  transition: "all 0.15s",
                }}
              >
                <div style={{ fontSize: 20, marginBottom: 4 }}>{t.icon}</div>
                <div style={{ fontSize: 11, fontFamily: FONTS.mono, fontWeight: 600 }}>
                  {t.label}
                </div>
              </button>
            ))}
          </div>
        </FormField>

        <FormField label="어댑터">
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginTop: 4 }}>
            {(ADAPTERS[selectedType] ?? []).map((a) => (
              <button
                key={a}
                onClick={() => setSelectedAdapter(a)}
                style={{
                  padding: "7px 14px",
                  borderRadius: 8,
                  border: `1px solid ${selectedAdapter === a ? COLORS.accent : COLORS.border}`,
                  background:
                    selectedAdapter === a ? COLORS.accentDim + "30" : COLORS.surfaceHover,
                  color: selectedAdapter === a ? COLORS.accent : COLORS.textMuted,
                  cursor: "pointer",
                  fontSize: 12,
                  fontFamily: FONTS.mono,
                }}
              >
                {a}
              </button>
            ))}
          </div>
        </FormField>

        {selectedType === "database" &&
          DB_FIELDS.map((field) => (
            <FormField key={field} label={field}>
              <input
                type={field === "비밀번호" ? "password" : "text"}
                placeholder={field === "포트" ? "5432" : field === "연결 ID" ? "my-database" : ""}
                style={inputStyle}
                value={form[field] ?? ""}
                onChange={(e) => setForm((p) => ({ ...p, [field]: e.target.value }))}
              />
            </FormField>
          ))}

        {selectedType === "llm" && (
          <>
            <FormField label="연결 ID">
              <input
                style={{ ...inputStyle, ...(editingConn ? { opacity: 0.6, cursor: "not-allowed" } : {}) }}
                readOnly={!!editingConn}
                value={form["연결 ID"] ?? ""}
                onChange={(e) => setForm((p) => ({ ...p, "연결 ID": e.target.value }))}
              />
            </FormField>
            <FormField label="이름">
              <input
                style={inputStyle}
                value={form["이름"] ?? ""}
                onChange={(e) => setForm((p) => ({ ...p, 이름: e.target.value }))}
              />
            </FormField>
            <FormField label="API Key">
              <input
                type="password"
                style={inputStyle}
                value={form["API Key"] ?? ""}
                onChange={(e) => setForm((p) => ({ ...p, "API Key": e.target.value }))}
              />
            </FormField>
            <FormField label="모델 (선택사항)">
              <input
                style={inputStyle}
                placeholder="claude-sonnet-4-6"
                value={form["모델"] ?? ""}
                onChange={(e) => setForm((p) => ({ ...p, 모델: e.target.value }))}
              />
            </FormField>
          </>
        )}

        {(selectedType === "messaging" || selectedType === "realtime") && (
          <>
            <FormField label="연결 ID">
              <input
                style={inputStyle}
                value={form["연결 ID"] ?? ""}
                onChange={(e) => setForm((p) => ({ ...p, "연결 ID": e.target.value }))}
              />
            </FormField>
            <FormField label="이름">
              <input
                style={inputStyle}
                value={form["이름"] ?? ""}
                onChange={(e) => setForm((p) => ({ ...p, 이름: e.target.value }))}
              />
            </FormField>
            <FormField label="URL / Endpoint">
              <input
                style={inputStyle}
                value={form["URL"] ?? ""}
                onChange={(e) => setForm((p) => ({ ...p, URL: e.target.value }))}
              />
            </FormField>
            <FormField label="Token / Webhook Key">
              <input
                type="password"
                style={inputStyle}
                value={form["Token"] ?? ""}
                onChange={(e) => setForm((p) => ({ ...p, Token: e.target.value }))}
              />
            </FormField>
          </>
        )}

        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 8 }}>
          <ActionButton variant="ghost" onClick={() => { setShowModal(false); setEditingConn(null); }}>
            취소
          </ActionButton>
          <ActionButton
            variant="primary"
            icon="💾"
            disabled={createConnection.isPending || updateConnection.isPending}
            onClick={handleSave}
          >
            {editingConn ? "수정" : "저장"}
          </ActionButton>
        </div>
      </Modal>
    </div>
  );
}
