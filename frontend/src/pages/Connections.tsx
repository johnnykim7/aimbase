import { useState } from "react";
import { cn } from "@/lib/utils";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { Modal } from "../components/common/Modal";
import { FormField, inputStyle } from "../components/common/FormField";
import { EmptyState } from "../components/common/EmptyState";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { Page } from "../components/layout/Page";
import { PlugZap } from "lucide-react";
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
      "연결 이름": conn.name ?? conn.id,
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
    <Page
      actions={
        <ActionButton variant="primary" icon="+" onClick={openCreateModal}>
          새 연결
        </ActionButton>
      }
    >

      {connections.length === 0 ? (
        <EmptyState
          icon={<PlugZap className="size-6" />}
          title="연결된 시스템이 없습니다"
          description="DB, 메시징, LLM, 실시간 연결을 추가하세요"
          action={
            <ActionButton variant="primary" icon="+" onClick={openCreateModal}>
              새 연결 추가
            </ActionButton>
          }
        />
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
          {connections.map((conn: Connection) => {
            const testResult = testResults[conn.id];
            return (
              <div
                key={conn.id}
                className={cn(
                  "bg-card border border-border rounded-xl p-5 relative",
                  conn.status === "connected" ? "border-l-[3px] border-l-success" : "border-l-[3px] border-l-warning"
                )}
              >
                <div className="flex justify-between items-start mb-3">
                  <div className="flex gap-2.5 items-center">
                    <span className="text-2xl">{getIcon(conn)}</span>
                    <div>
                      <div className="text-sm font-semibold font-mono text-foreground">
                        {conn.name || conn.id}
                      </div>
                      <div className="text-xs text-muted-foreground">{conn.adapter}</div>
                    </div>
                  </div>
                  <Badge color={statusColor(conn.status)}>{conn.type}</Badge>
                </div>

                <div className="text-xs font-mono text-muted-foreground/60 mb-2">
                  {conn.name}
                </div>

                {testResult && (
                  <div className={cn("text-[11px] font-mono mb-2", testResult.ok ? "text-success" : "text-destructive")}>
                    {testResult.ok ? `✓ 연결 성공 (${testResult.latencyMs}ms)` : "✕ 연결 실패"}
                  </div>
                )}

                <div className="flex justify-between items-center">
                  <span className="text-[11px] font-mono text-muted-foreground/60">
                    {conn.lastHealthCheckAt
                      ? `${new Date(conn.lastHealthCheckAt).toLocaleTimeString("ko-KR")} 확인`
                      : conn.status ?? "—"}
                  </span>
                  <div className="flex gap-1">
                    <ActionButton variant="ghost" small onClick={() => openEditModal(conn)}>수정</ActionButton>
                    <ActionButton variant="ghost" small disabled={testConnection.isPending} onClick={() => handleTest(conn.id)}>테스트</ActionButton>
                    <ActionButton variant="ghost" small onClick={() => deleteConnection.mutate(conn.id)}>삭제</ActionButton>
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
          <div className="flex gap-2 mt-2">
            {CONNECTION_TYPES.map((t) => (
              <button
                key={t.id}
                onClick={() => {
                  setSelectedType(t.id);
                  setSelectedAdapter(ADAPTERS[t.id]?.[0] ?? "");
                }}
                className={cn(
                  "flex-1 py-3 px-2 rounded-lg border text-center cursor-pointer transition-all",
                  selectedType === t.id
                    ? "border-primary bg-primary/10 text-primary"
                    : "border-border bg-accent text-muted-foreground"
                )}
              >
                <div className="text-xl mb-1">{t.icon}</div>
                <div className="text-[11px] font-mono font-semibold">{t.label}</div>
              </button>
            ))}
          </div>
        </FormField>

        <FormField label="어댑터">
          <div className="flex gap-2 flex-wrap mt-1">
            {(ADAPTERS[selectedType] ?? []).map((a) => (
              <button
                key={a}
                onClick={() => setSelectedAdapter(a)}
                className={cn(
                  "py-1.5 px-3.5 rounded-lg border text-xs font-mono cursor-pointer transition-all",
                  selectedAdapter === a
                    ? "border-primary bg-primary/10 text-primary"
                    : "border-border bg-accent text-muted-foreground"
                )}
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
              <input style={inputStyle} value={form["이름"] ?? ""} onChange={(e) => setForm((p) => ({ ...p, 이름: e.target.value }))} />
            </FormField>
            <FormField label="API Key">
              <input type="password" style={inputStyle} value={form["API Key"] ?? ""} onChange={(e) => setForm((p) => ({ ...p, "API Key": e.target.value }))} />
            </FormField>
            <FormField label="모델 (선택사항)">
              <input style={inputStyle} placeholder="claude-sonnet-4-6" value={form["모델"] ?? ""} onChange={(e) => setForm((p) => ({ ...p, 모델: e.target.value }))} />
            </FormField>
          </>
        )}

        {(selectedType === "messaging" || selectedType === "realtime") && (
          <>
            <FormField label="연결 ID">
              <input style={inputStyle} value={form["연결 ID"] ?? ""} onChange={(e) => setForm((p) => ({ ...p, "연결 ID": e.target.value }))} />
            </FormField>
            <FormField label="이름">
              <input style={inputStyle} value={form["이름"] ?? ""} onChange={(e) => setForm((p) => ({ ...p, 이름: e.target.value }))} />
            </FormField>
            <FormField label="URL / Endpoint">
              <input style={inputStyle} value={form["URL"] ?? ""} onChange={(e) => setForm((p) => ({ ...p, URL: e.target.value }))} />
            </FormField>
            <FormField label="Token / Webhook Key">
              <input type="password" style={inputStyle} value={form["Token"] ?? ""} onChange={(e) => setForm((p) => ({ ...p, Token: e.target.value }))} />
            </FormField>
          </>
        )}

        <div className="flex gap-2 justify-end mt-2">
          <ActionButton variant="ghost" onClick={() => { setShowModal(false); setEditingConn(null); }}>취소</ActionButton>
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
    </Page>
  );
}
