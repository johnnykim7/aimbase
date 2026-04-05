import { useState, useEffect } from "react";
import type { Node } from "@xyflow/react";
import { cn } from "@/lib/utils";
import { useConnections } from "../../hooks/useConnections";
import { usePrompts } from "../../hooks/usePrompts";
import { useMCPServers } from "../../hooks/useMCPServers";
import { useTools } from "../../hooks/useTools";
import type { Connection } from "../../types/connection";
import type { Prompt } from "../../types/prompt";
import type { MCPServer, MCPToolDef } from "../../types/mcp";

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

interface ConfigPanelProps {
  node: Node | null;
  onUpdate: (nodeId: string, data: Record<string, unknown>) => void;
  onClose: () => void;
  onDelete: (nodeId: string) => void;
}

interface SelectOption {
  value: string;
  label: string;
}

interface ConfigField {
  key: string;
  label: string;
  placeholder?: string;
  multiline?: boolean;
  type?: "text" | "select" | "multiselect";
  options?: SelectOption[];
  fetchOptions?: string;
  onSelectFill?: (value: string, allData: unknown[]) => Record<string, string>;
}

/* ------------------------------------------------------------------ */
/*  Component                                                          */
/* ------------------------------------------------------------------ */

type TabKey = "basic" | "prompt" | "tool" | "schema";

const TAB_LABELS: Record<TabKey, string> = {
  basic: "기본",
  prompt: "프롬프트",
  tool: "도구",
  schema: "스키마",
};

const LLM_TAB_KEYS: Record<TabKey, string[]> = {
  basic: ["connection_id", "model", "temperature"],
  prompt: ["prompt", "system"],
  tool: ["tool_choice", "allowed_tools", "blocked_tools"],
  schema: ["response_schema"],
};

export function ConfigPanel({ node, onUpdate, onClose, onDelete }: ConfigPanelProps) {
  const [label, setLabel] = useState("");
  const [config, setConfig] = useState<Record<string, string>>({});
  const [activeTab, setActiveTab] = useState<TabKey>("basic");
  const [showSaved, setShowSaved] = useState(false);

  const { data: connections, isError: connError } = useConnections();
  const { data: prompts, isError: promptError } = usePrompts();
  const { data: mcpServers, isError: mcpError } = useMCPServers();
  const { data: nativeTools } = useTools();

  const toolOptions: SelectOption[] = (() => {
    const tools: SelectOption[] = [];
    // CR-029: 네이티브 Tool 1급 노출 (상단에 배치)
    if (nativeTools && Array.isArray(nativeTools)) {
      nativeTools.forEach((t: { name: string; description?: string; contract?: { readOnly?: boolean } }) => {
        tools.push({
          value: t.name,
          label: `${t.name}${t.description ? ` — ${t.description}` : ""} (Native${t.contract?.readOnly ? ", ReadOnly" : ""})`,
        });
      });
    }
    // MCP 도구
    if (!mcpError && mcpServers) {
      (mcpServers as MCPServer[]).forEach((server) => {
        const serverTools: MCPToolDef[] = server.discoveredTools ?? server.toolsCache ?? [];
        serverTools.forEach((t) => {
          tools.push({
            value: t.name,
            label: `${t.name}${t.description ? ` — ${t.description}` : ""} (${server.name})`,
          });
        });
      });
    }
    return tools;
  })();

  const connectionOptions: SelectOption[] = (() => {
    if (connError || !connections) return [];
    return (connections as Connection[]).map((c) => ({
      value: c.id,
      label: `${c.name} (${c.type})`,
    }));
  })();

  const promptOptions: SelectOption[] = (() => {
    if (promptError || !prompts) return [];
    return (prompts as Prompt[]).map((p) => ({
      value: p.id,
      label: `${p.name ?? p.id}${p.domain ? ` [${p.domain}]` : ""}`,
    }));
  })();

  useEffect(() => {
    if (node) {
      setLabel((node.data.label as string) ?? "");
      const raw = (node.data.config as Record<string, unknown>) ?? {};
      const flat: Record<string, string> = {};
      Object.entries(raw).forEach(([k, v]) => {
        if (v != null && typeof v === "object") {
          flat[k] = JSON.stringify(v, null, 2);
        } else {
          flat[k] = String(v ?? "");
        }
      });
      setConfig(flat);
      setActiveTab("basic");
    }
  }, [node?.id]);

  if (!node) return null;

  const stepType = (node.data.type as string) ?? "action";

  const JSON_CONFIG_KEYS = ["response_schema", "input"];

  const handleSave = () => {
    const parsed: Record<string, unknown> = { ...config };
    JSON_CONFIG_KEYS.forEach((key) => {
      const val = config[key];
      if (val && typeof val === "string" && val.trim().startsWith("{")) {
        try { parsed[key] = JSON.parse(val); } catch { /* keep as string */ }
      }
    });
    onUpdate(node.id, { ...node.data, label, config: parsed });
    setShowSaved(true);
    setTimeout(() => setShowSaved(false), 1500);
  };

  const handlePromptSelect = (promptId: string) => {
    setConfig((c) => ({ ...c, promptTemplate: promptId }));
    if (!promptId) return;
    const found = (prompts as Prompt[] | undefined)?.find((p) => p.id === promptId);
    if (found?.content) {
      setConfig((c) => ({ ...c, prompt: found.content! }));
    }
  };

  const configFields = getConfigFields(stepType, {
    connectionOptions,
    connError,
    toolOptions,
    mcpError,
    promptOptions,
    promptError,
    handlePromptSelect,
  });

  const renderField = (field: ConfigField) => {
    if (field.type === "select") {
      const hasOptions = field.options && field.options.length > 0;
      if (!hasOptions) {
        return (
          <input
            value={config[field.key] ?? ""}
            onChange={(e) => setConfig((c) => ({ ...c, [field.key]: e.target.value }))}
            className="w-full py-2 px-2.5 rounded-lg border border-border bg-background text-xs font-mono text-foreground outline-none box-border"
            placeholder={field.placeholder}
          />
        );
      }
      return (
        <select
          value={config[field.key] ?? ""}
          onChange={(e) => {
            const val = e.target.value;
            if (field.onSelectFill) {
              const fills = field.onSelectFill(val, []);
              setConfig((c) => ({ ...c, [field.key]: val, ...fills }));
            } else {
              setConfig((c) => ({ ...c, [field.key]: val }));
            }
          }}
          className="w-full py-2 px-2.5 rounded-lg border border-border bg-background text-xs font-mono text-foreground outline-none box-border cursor-pointer"
        >
          <option value="">선택하세요...</option>
          {field.options!.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
      );
    }

    if (field.type === "multiselect") {
      const hasOptions = field.options && field.options.length > 0;
      if (!hasOptions) {
        return (
          <input
            value={config[field.key] ?? ""}
            onChange={(e) => setConfig((c) => ({ ...c, [field.key]: e.target.value }))}
            className="w-full py-2 px-2.5 rounded-lg border border-border bg-background text-xs font-mono text-foreground outline-none box-border"
            placeholder={field.placeholder}
          />
        );
      }
      const selected = (config[field.key] ?? "").split(",").map((s) => s.trim()).filter(Boolean);
      return (
        <div className="border border-border rounded-lg bg-background py-1.5 px-2 max-h-[120px] overflow-y-auto">
          {field.options!.map((opt) => {
            const checked = selected.includes(opt.value);
            return (
              <label
                key={opt.value}
                className="flex items-center gap-1.5 text-[11px] font-mono text-foreground py-0.5 cursor-pointer"
              >
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => {
                    const next = checked
                      ? selected.filter((s) => s !== opt.value)
                      : [...selected, opt.value];
                    setConfig((c) => ({ ...c, [field.key]: next.join(", ") }));
                  }}
                  className="accent-primary"
                />
                {opt.label}
              </label>
            );
          })}
        </div>
      );
    }

    if (field.multiline) {
      const isLargeField = ["prompt", "system", "response_schema"].includes(field.key);
      const isSchemaField = field.key === "response_schema";
      return (
        <textarea
          value={config[field.key] ?? ""}
          onChange={(e) => setConfig((c) => ({ ...c, [field.key]: e.target.value }))}
          rows={isSchemaField ? undefined : isLargeField ? 8 : 3}
          className="w-full py-2 px-2.5 rounded-lg border border-border bg-background text-xs font-mono text-foreground outline-none box-border resize-y"
          style={{
            flex: isSchemaField ? 1 : isLargeField ? 1 : undefined,
            minHeight: isSchemaField ? 200 : isLargeField ? 120 : undefined,
          }}
          placeholder={field.placeholder}
        />
      );
    }

    return (
      <input
        value={config[field.key] ?? ""}
        onChange={(e) => setConfig((c) => ({ ...c, [field.key]: e.target.value }))}
        className="w-full py-2 px-2.5 rounded-lg border border-border bg-background text-xs font-mono text-foreground outline-none box-border"
        placeholder={field.placeholder}
      />
    );
  };

  const isLlmType = stepType === "LLM_CALL" || stepType === "llm";

  const visibleFields = isLlmType
    ? configFields.filter((f) => LLM_TAB_KEYS[activeTab]?.includes(f.key))
    : configFields;

  const isSchemaTab = isLlmType && activeTab === "schema";

  return (
    <div className="w-[420px] bg-card border-l border-border p-4 flex flex-col gap-3 shrink-0 overflow-hidden">
      {/* 헤더: 노드 설정 + 저장/삭제 버튼 */}
      <div className="flex items-center gap-2 shrink-0">
        <div className="text-[13px] font-semibold text-foreground mr-auto">
          노드 설정
        </div>
        <div className="flex items-center gap-1">
          {showSaved && (
            <span className="text-[11px] text-primary font-semibold">
              저장됨
            </span>
          )}
          <button
            onClick={handleSave}
            className="py-1 px-2.5 rounded-md border-none bg-primary text-white text-[11px] font-semibold cursor-pointer"
          >
            저장
          </button>
          <button
            onClick={() => onDelete(node.id)}
            className="py-1 px-1.5 rounded-md border border-destructive bg-transparent text-destructive text-[11px] font-semibold cursor-pointer"
          >
            삭제
          </button>
          <button
            onClick={onClose}
            className="bg-transparent border-none cursor-pointer text-base text-muted-foreground/40 p-0.5 hover:text-foreground"
          >
            ✕
          </button>
        </div>
      </div>

      {/* 이름 + 타입 */}
      <div className="shrink-0">
        <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-wider mb-1">이름</div>
        <input
          value={label}
          onChange={(e) => setLabel(e.target.value)}
          className="w-full py-2 px-2.5 rounded-lg border border-border bg-background text-xs font-mono text-foreground outline-none box-border"
        />
      </div>

      <div className="shrink-0">
        <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-wider mb-1">타입</div>
        <div className="py-2 px-2.5 rounded-lg bg-background text-xs font-mono text-muted-foreground">
          {stepType}
        </div>
      </div>

      {/* LLM_CALL: 탭 바 */}
      {isLlmType && (
        <div className="flex border-b border-border -mb-1 shrink-0">
          {(Object.keys(TAB_LABELS) as TabKey[]).map((tab) => (
            <button
              key={tab}
              onClick={() => setActiveTab(tab)}
              className={cn(
                "flex-1 py-1.5 text-[11px] bg-transparent border-none cursor-pointer transition-colors -mb-px",
                activeTab === tab
                  ? "font-semibold text-primary border-b-2 border-b-primary"
                  : "font-normal text-muted-foreground border-b-2 border-b-transparent"
              )}
            >
              {TAB_LABELS[tab]}
            </button>
          ))}
        </div>
      )}

      {/* 필드 영역 — 남은 공간 채움 */}
      <div className="flex-1 min-h-0 overflow-y-auto flex flex-col gap-3">
        {/* LLM_CALL 프롬프트 탭: 프롬프트 템플릿 선택 */}
        {isLlmType && activeTab === "prompt" && (
          <div className="shrink-0">
            <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-wider mb-1">프롬프트 템플릿</div>
            {promptError || promptOptions.length === 0 ? (
              <input
                value={config.promptTemplate ?? ""}
                onChange={(e) => setConfig((c) => ({ ...c, promptTemplate: e.target.value }))}
                className="w-full py-2 px-2.5 rounded-lg border border-border bg-background text-xs font-mono text-foreground outline-none box-border"
                placeholder="프롬프트 ID (목록 로딩 불가)"
              />
            ) : (
              <select
                value={config.promptTemplate ?? ""}
                onChange={(e) => handlePromptSelect(e.target.value)}
                className="w-full py-2 px-2.5 rounded-lg border border-border bg-background text-xs font-mono text-foreground outline-none box-border cursor-pointer"
              >
                <option value="">직접 입력</option>
                {promptOptions.map((opt) => (
                  <option key={opt.value} value={opt.value}>
                    {opt.label}
                  </option>
                ))}
              </select>
            )}
            <div className="text-[10px] text-muted-foreground/40 mt-0.5">
              선택 시 프롬프트 내용이 자동으로 채워집니다
            </div>
          </div>
        )}

        {visibleFields.map((field) => (
          <div key={field.key} className="flex flex-col" style={{ flex: isSchemaTab ? 1 : undefined, minHeight: isSchemaTab ? 0 : undefined }}>
            <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-wider mb-1 shrink-0">{field.label}</div>
            {renderField(field)}
          </div>
        ))}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Config field definitions per step type                             */
/* ------------------------------------------------------------------ */

interface FieldContext {
  connectionOptions: SelectOption[];
  connError: boolean;
  toolOptions: SelectOption[];
  mcpError: boolean;
  promptOptions: SelectOption[];
  promptError: boolean;
  handlePromptSelect: (id: string) => void;
}

function getConfigFields(type: string, ctx: FieldContext): ConfigField[] {
  switch (type) {
    case "LLM_CALL":
    case "llm":
      return [
        {
          key: "connection_id",
          label: "LLM 연결",
          placeholder: ctx.connError ? "연결 ID 직접 입력" : "연결을 선택하세요 (비우면 자동 라우팅)",
          type: ctx.connError ? "text" : "select",
          options: ctx.connectionOptions,
        },
        { key: "model", label: "모델", placeholder: "예: claude-sonnet-4-20250514, auto" },
        { key: "prompt", label: "프롬프트", placeholder: "{{input}} 변수 사용 가능", multiline: true },
        { key: "system", label: "시스템 메시지", placeholder: "선택사항", multiline: true },
        { key: "temperature", label: "Temperature", placeholder: "0.7" },
        { key: "tool_choice", label: "도구 선택 (CR-006)", placeholder: "auto | none | required | {tool_name}" },
        {
          key: "allowed_tools",
          label: "허용 도구 (쉼표 구분)",
          placeholder: "calculator, web_search",
          type: ctx.mcpError || ctx.toolOptions.length === 0 ? "text" : "multiselect",
          options: ctx.toolOptions,
        },
        {
          key: "blocked_tools",
          label: "차단 도구 (쉼표 구분)",
          placeholder: "file_write",
          type: ctx.mcpError || ctx.toolOptions.length === 0 ? "text" : "multiselect",
          options: ctx.toolOptions,
        },
        { key: "response_schema", label: "응답 스키마 (JSON)", placeholder: '{"type":"object","properties":{...}}', multiline: true },
      ];
    case "TOOL_CALL":
    case "TOOL_USE":
    case "tool_use":
    case "tool":
      return [
        {
          key: "tool",
          label: "도구 이름",
          placeholder: ctx.mcpError ? "도구 이름 직접 입력" : "도구를 선택하세요",
          type: ctx.mcpError || ctx.toolOptions.length === 0 ? "text" : "select",
          options: ctx.toolOptions,
        },
        { key: "input", label: "입력 매핑 (JSON)", placeholder: '{"expression": "{{step1.output}}"}', multiline: true },
        { key: "description", label: "스텝 설명", placeholder: "이 도구가 수행하는 작업 설명" },
        { key: "timeoutMs", label: "타임아웃 (ms)", placeholder: "30000 (기본: 없음)" },
        { key: "onSuccess", label: "성공 시 다음 스텝 ID", placeholder: "예: step_3 (비우면 DAG 순서)" },
        { key: "onFailure", label: "실패 시 다음 스텝 ID", placeholder: "예: error_handler (비우면 워크플로우 중단)" },
        { key: "outputKey", label: "출력 변수 키", placeholder: "output (기본값)" },
      ];
    case "CONDITION":
    case "condition":
      return [
        { key: "expression", label: "조건식", placeholder: "예: result.score > 0.8", multiline: true },
        { key: "trueLabel", label: "참 라벨", placeholder: "true" },
        { key: "falseLabel", label: "거짓 라벨", placeholder: "false" },
      ];
    case "PARALLEL":
    case "parallel":
      return [
        { key: "maxConcurrency", label: "최대 동시 실행", placeholder: "3" },
      ];
    case "HUMAN_INPUT":
    case "approval":
      return [
        { key: "approver", label: "승인자", placeholder: "예: admin, manager" },
        { key: "message", label: "승인 메시지", placeholder: "승인 요청 메시지", multiline: true },
      ];
    case "ACTION":
    case "action":
      return [
        { key: "type", label: "액션 유형", placeholder: "WRITE | NOTIFY | WRITE_AND_NOTIFY" },
        { key: "adapter", label: "어댑터", placeholder: "postgresql" },
        { key: "destination", label: "대상 (테이블/채널)", placeholder: "results" },
        { key: "payload", label: "페이로드 (JSON)", placeholder: '{"data": {"key": "{{step1.output}}"}}', multiline: true },
      ];
    default:
      return [];
  }
}
