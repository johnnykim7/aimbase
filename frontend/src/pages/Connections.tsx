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
import {
  WidgetModelsField,
  hasWidgetModelsErrors,
  parseWidgetModels,
  serializeWidgetModels,
  validateWidgetModels,
  type WidgetModel,
  type WidgetModelsErrors,
} from "../components/connections/WidgetModelsField";
import {
  ImageGenerationFields,
  parseImageGeneration,
  serializeImageGeneration,
  type ImageConnectionOption,
  type ImageGenerationValues,
} from "../components/connections/ImageGenerationFields";

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
  llm: ["Claude (Anthropic)", "Claude CLI", "OpenAI", "Ollama", "OpenAI Compatible", "AWS Bedrock", "Vertex AI"],
  realtime: ["WebSocket", "SSE"],
};

const CLI_ADAPTER_VALUES = ["anthropic-cli", "claude-cli", "claude cli", "anthropic cli", "claude-max", "claude-pro"];
const OPENAI_COMPATIBLE_HINTS = ["deepseek", "groq", "mistral", "openrouter", "together", "fireworks", "lm studio", "localai", "vllm"];

// 저장된 어댑터 값은 서버 이름(anthropic-cli 등)이고 수정 창은 표시명으로 전용 칸을 고른다.
// 서버 ConnectionAdapterFactory.normalizeAdapterType 과 같은 순서로 표시명을 찾고, 못 찾으면 저장값을 그대로 쓴다.
function displayAdapter(type: string, stored: string): string {
  if (type !== "llm") return stored;
  const s = stored.toLowerCase();
  if (CLI_ADAPTER_VALUES.includes(s)) return "Claude CLI";
  if (s.includes("anthropic") || s.includes("claude")) return "Claude (Anthropic)";
  if (s === "openai_compatible" || s === "openai compatible" || OPENAI_COMPATIBLE_HINTS.some((h) => s.includes(h))) return "OpenAI Compatible";
  if (s.includes("openai") || s.includes("gpt")) return "OpenAI";
  if (s.includes("ollama")) return "Ollama";
  if (s.includes("bedrock") || s.includes("aws")) return "AWS Bedrock";
  if (s.includes("vertex") || s.includes("google") || s.includes("gemini")) return "Vertex AI";
  return stored;
}

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
  const [widgetModels, setWidgetModels] = useState<WidgetModel[]>([]);
  const [widgetModelsErrors, setWidgetModelsErrors] = useState<WidgetModelsErrors | undefined>();
  const [imageGen, setImageGen] = useState<ImageGenerationValues>(() => parseImageGeneration({}));

  const handleTest = async (id: string) => {
    const result = await testConnection.mutateAsync(id);
    setTestResults((prev) => ({ ...prev, [id]: result }));
  };

  const openCreateModal = () => {
    setEditingConn(null);
    setForm({});
    setWidgetModels([]);
    setWidgetModelsErrors(undefined);
    setImageGen(parseImageGeneration({}));
    setSelectedType("database");
    setSelectedAdapter("PostgreSQL");
    setShowModal(true);
  };

  const openEditModal = (conn: Connection) => {
    setEditingConn(conn);
    setSelectedType(conn.type ?? "llm");
    setSelectedAdapter(displayAdapter(conn.type ?? "llm", conn.adapter ?? ""));
    const cfg = conn.config ?? {};
    setWidgetModels(parseWidgetModels(cfg.models));
    setWidgetModelsErrors(undefined);
    setImageGen(parseImageGeneration(cfg));
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
      // CR-032: 프로바이더 확장 필드
      "Base URL": (cfg.base_url as string) ?? "",
      "AWS Region": (cfg.aws_region as string) ?? "",
      "AWS Access Key ID": (cfg.aws_access_key_id as string) ?? "",
      "AWS Secret Access Key": (cfg.aws_secret_access_key as string) ?? "",
      "GCP Project ID": (cfg.project_id as string) ?? "",
      "Location": (cfg.location as string) ?? "",
      "Service Account Key": (cfg.service_account_key as string) ?? "",
      // CR-071: Claude CLI 어댑터 전용
      "Tool Mode": (cfg.tool_mode as string) ?? "AIMBASE",
      "Config Dir": (cfg.config_dir as string) ?? "",
      "Runner API Key": (cfg.runner_api_key as string) ?? "",
      "System Prompt Override": (cfg.system_prompt_override as string) ?? "",
      // CR-117: CLI 본체 Agent 서브에이전트 허용 여부. 키 없음 = 기본 ON(현행).
      "Subagent Enabled": cfg.subagent_enabled === false || cfg.subagent_enabled === "false" ? "false" : "true",
      // CR-129: CLI 에 노출할 도구 화이트리스트(CSV). 배열로 저장돼 있으면 CSV 로 펼쳐 보여준다.
      "Exposed Tools": Array.isArray(cfg.exposed_tools)
        ? (cfg.exposed_tools as string[]).join(",")
        : ((cfg.exposed_tools as string) ?? ""),
    });
    setShowModal(true);
  };

  const handleSave = () => {
    if (selectedType === "llm") {
      const errors = validateWidgetModels(widgetModels, form["모델"] ?? "");
      if (hasWidgetModelsErrors(errors)) {
        setWidgetModelsErrors(errors);
        return;
      }
    }
    // 서버는 설정을 통째로 바꿔 끼운다. 이 창에 칸이 없는 값(실행기를 고르는 agent_name 등)을 잃지 않게 기존 설정을 깔고 덮는다.
    const config: Record<string, unknown> = {
      ...(editingConn?.config ?? {}),
      host: form["호스트"],
      port: form["포트"],
      database: form["데이터베이스"],
      username: form["사용자"],
      password: form["비밀번호"],
      url: form["URL"],
      apiKey: form["API Key"],
      token: form["Token"],
      model: form["모델"],
      // CR-175: 허용 모델. 비면 키를 저장하지 않는다(BIZ-150)
      models: selectedType === "llm" ? serializeWidgetModels(widgetModels) : undefined,
      // CR-032: 프로바이더 확장 필드
      base_url: form["Base URL"],
      aws_region: form["AWS Region"],
      aws_access_key_id: form["AWS Access Key ID"],
      aws_secret_access_key: form["AWS Secret Access Key"],
      project_id: form["GCP Project ID"],
      location: form["Location"],
      service_account_key: form["Service Account Key"],
      // CR-071: Claude CLI 어댑터 전용 (BE 키명과 일치)
      tool_mode: form["Tool Mode"],
      config_dir: form["Config Dir"],
      runner_api_key: form["Runner API Key"],
      system_prompt_override: form["System Prompt Override"],
      // CR-117: "false" 일 때만 차단 — 그 외(미설정 포함)는 true(현행 유지)
      subagent_enabled: form["Subagent Enabled"] === "false" ? false : true,
      // CR-129: CSV 입력을 배열로 정규화해 저장. 비면 undefined → 키 미저장 = 전체 노출(기존 동작).
      exposed_tools: (() => {
        const raw = (form["Exposed Tools"] ?? "").trim();
        if (!raw) return undefined;
        const list = raw.split(",").map((t) => t.trim()).filter(Boolean);
        return list.length > 0 ? list : undefined;
      })(),
      // CR-184: 그림 칸. LLM 연결이 아니면 키를 모두 뺀다
      ...(selectedType === "llm"
        ? serializeImageGeneration(imageGen, selectedAdapter === "OpenAI")
        : { image_model: undefined, image_quality: undefined, image_connection_id: undefined }),
    };

    if (editingConn) {
      // 어댑터를 바꾸지 않았으면 저장값을 그대로 보낸다. 서버가 기본 CLI 연결을 adapter 원문으로 찾는다.
      const storedAdapter = editingConn.adapter ?? "";
      const adapter = displayAdapter(selectedType, storedAdapter) === selectedAdapter ? storedAdapter : selectedAdapter;
      updateConnection.mutate(
        { id: editingConn.id, data: { id: editingConn.id, name: form["이름"] ?? editingConn.name, adapter, type: selectedType, config } },
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

  // CR-184: 그림 연결로 고를 수 있는 연결. 서버 조건과 같이 OpenAI 이면서 그림 모델이 있는 것만, 편집 중인 자기는 지금 칸 값으로 본다.
  const imageConnectionOptions: ImageConnectionOption[] = connections
    .filter(
      (c: Connection) =>
        c.id !== editingConn?.id &&
        (c.type ?? "llm") === "llm" &&
        displayAdapter("llm", c.adapter ?? "") === "OpenAI" &&
        typeof c.config?.image_model === "string" &&
        c.config.image_model.trim() !== "",
    )
    .map((c: Connection) => ({ id: c.id, label: c.name || c.id }));
  if (editingConn && selectedAdapter === "OpenAI" && imageGen.imageModel.trim() !== "") {
    imageConnectionOptions.unshift({ id: editingConn.id, label: form["이름"] || editingConn.name || editingConn.id });
  }

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

            {/* CR-032: OpenAI Compatible — base_url 필수 */}
            {(selectedAdapter === "OpenAI Compatible" || selectedAdapter === "AWS Bedrock" || selectedAdapter === "Vertex AI") && (
              <FormField label="Base URL *">
                <input
                  style={inputStyle}
                  placeholder={
                    selectedAdapter === "OpenAI Compatible" ? "https://api.deepseek.com/v1" :
                    selectedAdapter === "AWS Bedrock" ? "https://bedrock-proxy.example.com/v1" :
                    "https://vertex-proxy.example.com/v1"
                  }
                  value={form["Base URL"] ?? ""}
                  onChange={(e) => setForm((p) => ({ ...p, "Base URL": e.target.value }))}
                />
              </FormField>
            )}

            {/* Claude CLI(anthropic-cli)는 OAuth(CLAUDE_CONFIG_DIR) 기반 — API Key 불필요 */}
            {selectedAdapter !== "Claude CLI" && (
              <FormField label="API Key">
                <input type="password" style={inputStyle} value={form["API Key"] ?? ""} onChange={(e) => setForm((p) => ({ ...p, "API Key": e.target.value }))} />
              </FormField>
            )}

            {/* CR-071: Claude CLI 어댑터 전용 필드 (BE ConnectionAdapterFactory.createAdapter 'anthropic-cli' 분기와 키 일치) */}
            {selectedAdapter === "Claude CLI" && (
              <>
                <FormField label="Tool Mode">
                  <select
                    style={inputStyle}
                    value={form["Tool Mode"] ?? "AIMBASE"}
                    onChange={(e) => setForm((p) => ({ ...p, "Tool Mode": e.target.value }))}
                  >
                    <option value="AIMBASE">AIMBASE (Aimbase MCP 도구만)</option>
                    <option value="NATIVE">NATIVE (CLI 네이티브 도구만)</option>
                    <option value="HYBRID">HYBRID (둘 다)</option>
                  </select>
                </FormField>
                <FormField label="Config Dir (선택)">
                  <input style={inputStyle} placeholder="/data/agents/<id>/.claude" value={form["Config Dir"] ?? ""} onChange={(e) => setForm((p) => ({ ...p, "Config Dir": e.target.value }))} />
                </FormField>
                <FormField label="Runner API Key (선택)">
                  <input type="password" style={inputStyle} placeholder="Runner 인증용 (미지정 시 무인증)" value={form["Runner API Key"] ?? ""} onChange={(e) => setForm((p) => ({ ...p, "Runner API Key": e.target.value }))} />
                </FormField>
                <FormField label="System Prompt Override (선택)">
                  <textarea style={{ ...inputStyle, minHeight: 80 }} placeholder="--append-system-prompt 로 주입할 추가 지침" value={form["System Prompt Override"] ?? ""} onChange={(e) => setForm((p) => ({ ...p, "System Prompt Override": e.target.value }))} />
                </FormField>
                {/* CR-129: 이 커넥션이 CLI 에 노출할 도구 화이트리스트. 비우면 전체(~110개) 노출(기존 동작). */}
                <FormField label="Exposed Tools (선택)">
                  <textarea
                    style={{ ...inputStyle, minHeight: 60 }}
                    placeholder="쉼표로 구분. 비우면 전체 노출 (예: get_progress,get_bottleneck,bash,get_current_time)"
                    value={form["Exposed Tools"] ?? ""}
                    onChange={(e) => setForm((p) => ({ ...p, "Exposed Tools": e.target.value }))}
                  />
                  <div style={{ fontSize: 12, color: "var(--tw-gray-600)", marginTop: 4 }}>
                    비우면 서버 MCP 노출 도구 전체(~110개)가 CLI 에 실립니다. 소비앱이 실제 쓰는 도구만 지정하면
                    CLI 가 도구를 고르기 쉬워지고 초기 지연도 줄어듭니다.
                  </div>
                </FormField>
                {/* CR-117: CLI 본체 Agent 서브에이전트 허용 토글. 기본 ON(현행). OFF 면 --disallowedTools Agent 주입. */}
                <FormField label="Subagent 허용">
                  <label style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 13, cursor: "pointer" }}>
                    <input
                      type="checkbox"
                      checked={form["Subagent Enabled"] !== "false"}
                      onChange={(e) => setForm((p) => ({ ...p, "Subagent Enabled": e.target.checked ? "true" : "false" }))}
                    />
                    <span>CLI 자율 서브에이전트(Agent) 허용 — 끄면 spawn 차단. 제어는 불가하고 spawn 여부만 결정합니다.</span>
                  </label>
                </FormField>
              </>
            )}

            {/* CR-032: Bedrock 전용 필드 */}
            {selectedAdapter === "AWS Bedrock" && (
              <>
                <FormField label="AWS Region">
                  <input style={inputStyle} placeholder="us-east-1" value={form["AWS Region"] ?? ""} onChange={(e) => setForm((p) => ({ ...p, "AWS Region": e.target.value }))} />
                </FormField>
                <FormField label="AWS Access Key ID">
                  <input type="password" style={inputStyle} value={form["AWS Access Key ID"] ?? ""} onChange={(e) => setForm((p) => ({ ...p, "AWS Access Key ID": e.target.value }))} />
                </FormField>
                <FormField label="AWS Secret Access Key">
                  <input type="password" style={inputStyle} value={form["AWS Secret Access Key"] ?? ""} onChange={(e) => setForm((p) => ({ ...p, "AWS Secret Access Key": e.target.value }))} />
                </FormField>
              </>
            )}

            {/* CR-032: Vertex AI 전용 필드 */}
            {selectedAdapter === "Vertex AI" && (
              <>
                <FormField label="GCP Project ID">
                  <input style={inputStyle} placeholder="my-gcp-project" value={form["GCP Project ID"] ?? ""} onChange={(e) => setForm((p) => ({ ...p, "GCP Project ID": e.target.value }))} />
                </FormField>
                <FormField label="Location">
                  <input style={inputStyle} placeholder="us-central1" value={form["Location"] ?? ""} onChange={(e) => setForm((p) => ({ ...p, Location: e.target.value }))} />
                </FormField>
                <FormField label="Service Account Key (JSON)">
                  <textarea style={{ ...inputStyle, minHeight: 80 }} placeholder='{"type":"service_account",...}' value={form["Service Account Key"] ?? ""} onChange={(e) => setForm((p) => ({ ...p, "Service Account Key": e.target.value }))} />
                </FormField>
              </>
            )}

            <FormField label="모델 (선택사항)">
              <input
                style={inputStyle}
                placeholder={
                  selectedAdapter === "Claude (Anthropic)" ? "claude-sonnet-4-6" :
                  selectedAdapter === "Claude CLI" ? "claude-sonnet-4-6 (미지정 시 CLI 기본)" :
                  selectedAdapter === "OpenAI" ? "gpt-4o" :
                  selectedAdapter === "Ollama" ? "llama3.2" :
                  selectedAdapter === "OpenAI Compatible" ? "deepseek-chat" :
                  selectedAdapter === "AWS Bedrock" ? "anthropic.claude-sonnet-4-5-20250514-v1:0" :
                  selectedAdapter === "Vertex AI" ? "gemini-2.0-flash" : ""
                }
                value={form["모델"] ?? ""}
                onChange={(e) => setForm((p) => ({ ...p, 모델: e.target.value }))}
              />
            </FormField>
            <WidgetModelsField
              rows={widgetModels}
              defaultModel={form["모델"] ?? ""}
              errors={widgetModelsErrors}
              onRowsChange={(rows) => { setWidgetModels(rows); setWidgetModelsErrors(undefined); }}
              onDefaultChange={(id) => { setForm((p) => ({ ...p, 모델: id })); setWidgetModelsErrors(undefined); }}
            />
            <ImageGenerationFields
              isOpenAi={selectedAdapter === "OpenAI"}
              values={imageGen}
              options={imageConnectionOptions}
              onChange={setImageGen}
            />
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
