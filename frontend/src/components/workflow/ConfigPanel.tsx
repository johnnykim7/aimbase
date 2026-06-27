import { useState, useEffect } from "react";
import type { Node } from "@xyflow/react";
import { cn } from "@/lib/utils";
import { useConnections } from "../../hooks/useConnections";
import { usePrompts } from "../../hooks/usePrompts";
import { useMCPServers } from "../../hooks/useMCPServers";
import { useTools } from "../../hooks/useTools";
import { usePlatformWorkflows } from "../../hooks/usePlatformWorkflows";
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
  const { data: platformWorkflows, isError: platformWfError } = usePlatformWorkflows();

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

  // CR-097: SUB_WORKFLOW workflow_id 드롭다운 — ID 비노출, name 표시 (feedback_id_display)
  const platformWorkflowOptions: SelectOption[] = (() => {
    if (platformWfError || !platformWorkflows) return [];
    return platformWorkflows.map((w) => ({
      value: w.id,
      label: w.name ?? w.id,
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

  // CR-055: EVALUATOR_LOOP의 generator/evaluator/pass_criteria는 중첩 객체로 저장되어야 함
  // CR-120: LARGE_INPUT output_schema(JSON), FOREACH body(중첩 스텝)도 객체 보존
  const JSON_CONFIG_KEYS = ["response_schema", "input", "generator", "evaluator", "pass_criteria", "output_schema", "body"];
  // CR-120: 쉼표 구분 문자열 → 배열로 직렬화할 키
  const CSV_ARRAY_KEYS = ["focus_areas"];

  const handleSave = () => {
    const parsed: Record<string, unknown> = { ...config };
    JSON_CONFIG_KEYS.forEach((key) => {
      const val = config[key];
      if (val && typeof val === "string") {
        const t = val.trim();
        if (t.startsWith("{") || t.startsWith("[")) {
          try { parsed[key] = JSON.parse(val); } catch { /* keep as string */ }
        }
      }
    });
    // CR-120: 쉼표 구분 → 배열 (빈 값은 키 제거)
    CSV_ARRAY_KEYS.forEach((key) => {
      const val = config[key];
      if (typeof val === "string") {
        const arr = val.split(",").map((s) => s.trim()).filter(Boolean);
        if (arr.length > 0) parsed[key] = arr;
        else delete parsed[key];
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
    platformWorkflowOptions,
    platformWfError,
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
  platformWorkflowOptions: SelectOption[];
  platformWfError: boolean;
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
    case "EVALUATOR_LOOP":
    case "evaluator_loop":
      return [
        {
          key: "max_iterations",
          label: "최대 반복 횟수 (1-10)",
          placeholder: "3 (기본)",
        },
        {
          key: "generator",
          label: "Generator (JSON)",
          placeholder:
            '{\n  "model": "auto",\n  "system": "...",\n  "prompt": "원문: {{input.text}}\\n{{#if loop.iteration}}피드백: {{loop.feedback}}{{/if}}",\n  "max_tokens": 4096\n}',
          multiline: true,
        },
        {
          key: "evaluator",
          label: "Evaluator (JSON)",
          placeholder:
            '{\n  "model": "auto",\n  "prompt_template_key": "evaluator.literary_critic",\n  "response_format": {\n    "type": "json_schema",\n    "schema": { "type": "object", "required": ["score","passed","feedback"], "properties": {"score":{"type":"number"},"passed":{"type":"boolean"},"feedback":{"type":"string"}} }\n  }\n}',
          multiline: true,
        },
        {
          key: "pass_criteria",
          label: "통과 조건 (JSON)",
          placeholder:
            '{\n  "type": "SCORE_THRESHOLD",\n  "field": "score",\n  "threshold": 8.5,\n  "operator": "GTE"\n}',
          multiline: true,
        },
      ];
    case "AGENT_CALL":
    case "agent":
      return [
        { key: "description", label: "에이전트 설명", placeholder: "코드 리뷰 에이전트 (3-5 단어)" },
        { key: "prompt", label: "프롬프트", placeholder: "{{input.code}}를 리뷰해줘", multiline: true },
        { key: "model", label: "모델", placeholder: "예: claude-sonnet (비우면 기본값)" },
        {
          key: "connection_id",
          label: "LLM 연결",
          placeholder: ctx.connError ? "연결 ID 직접 입력" : "연결 선택 (비우면 기본)",
          type: ctx.connError ? "text" : "select",
          options: ctx.connectionOptions,
        },
        {
          key: "isolation",
          label: "격리 모드",
          placeholder: "NONE",
          type: "select",
          options: [
            { value: "NONE", label: "NONE — 격리 없음" },
            { value: "WORKTREE", label: "WORKTREE — Git worktree 격리" },
          ],
        },
        { key: "timeout_ms", label: "타임아웃 (ms)", placeholder: "120000" },
        { key: "agents", label: "멀티에이전트 (JSON)", placeholder: '[{"description":"agent-1","prompt":"..."}]', multiline: true },
        {
          key: "execution",
          label: "실행 방식 (멀티)",
          placeholder: "parallel",
          type: "select",
          options: [
            { value: "parallel", label: "parallel — 병렬 실행" },
            { value: "sequential", label: "sequential — 순차 실행" },
          ],
        },
      ];
    case "SUB_WORKFLOW":
    case "sub_workflow":
      return [
        {
          key: "workflow_id",
          label: "공용 워크플로우",
          placeholder: ctx.platformWfError || ctx.platformWorkflowOptions.length === 0
            ? "워크플로우 ID 직접 입력"
            : "플랫폼 공용 워크플로우를 선택하세요",
          type: ctx.platformWfError || ctx.platformWorkflowOptions.length === 0 ? "text" : "select",
          options: ctx.platformWorkflowOptions,
        },
        {
          key: "input",
          label: "입력 매핑 (JSON)",
          placeholder: '{\n  "zip_path": "{{input.zip_path}}",\n  "prompt": "코드 리뷰해줘"\n}',
          multiline: true,
        },
      ];
    case "FOREACH":
    case "foreach":
      // body(중첩 스텝)는 캔버스 그룹 서브노드로 편집 — 여기선 반복 제어만.
      return [
        { key: "items", label: "반복 컬렉션", placeholder: "{{fetch.output.notices}} (List 변수 참조)" },
        { key: "item_var", label: "원소 변수명", placeholder: "item (기본값)" },
        {
          key: "mode",
          label: "실행 방식",
          placeholder: "sequential",
          type: "select",
          options: [
            { value: "sequential", label: "sequential — 순차" },
            { value: "parallel", label: "parallel — 병렬" },
          ],
        },
        { key: "max_concurrency", label: "최대 동시 실행 (parallel)", placeholder: "5 (기본)" },
        { key: "max_items", label: "처리 상한", placeholder: "100 (기본, 초과 시 FAIL)" },
        {
          key: "on_item_error",
          label: "원소 실패 시",
          placeholder: "fail",
          type: "select",
          options: [
            { value: "fail", label: "fail — 즉시 중단" },
            { value: "continue", label: "continue — 계속" },
          ],
        },
        {
          key: "collect",
          label: "결과 수집 방식",
          placeholder: "append",
          type: "select",
          options: [
            { value: "append", label: "append — List 누적" },
            { value: "merge", label: "merge — TipTap 문서 병합" },
            { value: "none", label: "none — 미수집" },
          ],
        },
      ];
    case "LARGE_INPUT":
    case "large_input":
      return [
        {
          key: "analysis_action",
          label: "분석 동작",
          placeholder: "extract",
          type: "select",
          options: [
            { value: "extract", label: "extract — 항목 추출" },
            { value: "summarize", label: "summarize — 요약" },
            { value: "verify", label: "verify — 기준 대조 검증 (reference 필수)" },
          ],
        },
        { key: "source_file", label: "소스 파일", placeholder: "attachment_id(UUID) 또는 작업장 경로 — {{item.targetPath}}" },
        { key: "input", label: "또는 인라인 텍스트", placeholder: "source_file 대신 직접 텍스트", multiline: true },
        {
          key: "connection_id",
          label: "LLM 연결",
          placeholder: ctx.connError ? "연결 ID 직접 입력" : "연결 선택 (비우면 자동 라우팅)",
          type: ctx.connError ? "text" : "select",
          options: ctx.connectionOptions,
        },
        { key: "model", label: "모델", placeholder: "auto (기본)" },
        { key: "custom_instruction", label: "도메인 지시문 (custom_instruction)", placeholder: "map/reduce 프롬프트에 주입될 범용 도메인 지시", multiline: true },
        { key: "analysis_goal", label: "분석 목적 (analysis_goal)", placeholder: "선택", multiline: true },
        { key: "reference_input", label: "비교 기준 (verify 전용)", placeholder: "verify 동작 시 필수 — 대조 기준", multiline: true },
        { key: "focus_areas", label: "집중 영역 (쉼표 구분)", placeholder: "가격, 납기, 위약금" },
        { key: "output_schema", label: "출력 스키마 (JSON)", placeholder: '{"type":"object","properties":{...}}', multiline: true },
        { key: "max_parallel", label: "청크 병렬 상한", placeholder: "5 (기본)" },
        { key: "item_retry_max", label: "청크 재시도 횟수", placeholder: "1 (기본)" },
        {
          key: "require_full_coverage",
          label: "전수 커버리지 강제",
          placeholder: "true",
          type: "select",
          options: [
            { value: "true", label: "true — 누락 청크 있으면 FAIL" },
            { value: "false", label: "false — 부분 결과 허용" },
          ],
        },
        {
          key: "large_input_mode",
          label: "분해 모드",
          placeholder: "auto",
          type: "select",
          options: [
            { value: "auto", label: "auto — 크기 따라 자동 (CLI는 force)" },
            { value: "force", label: "force — 항상 분해" },
            { value: "forbid", label: "forbid — 큰 입력 거부" },
            { value: "off", label: "off — 분해 안 함 (청크 1개)" },
          ],
        },
      ];
    default:
      return [];
  }
}
