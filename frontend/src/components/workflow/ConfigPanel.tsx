import { useState, useEffect } from "react";
import type { Node } from "@xyflow/react";
import { COLORS, FONTS } from "../../theme";

interface ConfigPanelProps {
  node: Node | null;
  onUpdate: (nodeId: string, data: Record<string, unknown>) => void;
  onClose: () => void;
  onDelete: (nodeId: string) => void;
}

export function ConfigPanel({ node, onUpdate, onClose, onDelete }: ConfigPanelProps) {
  const [label, setLabel] = useState("");
  const [config, setConfig] = useState<Record<string, string>>({});

  useEffect(() => {
    if (node) {
      setLabel((node.data.label as string) ?? "");
      const raw = (node.data.config as Record<string, unknown>) ?? {};
      const flat: Record<string, string> = {};
      Object.entries(raw).forEach(([k, v]) => (flat[k] = String(v ?? "")));
      setConfig(flat);
    }
  }, [node?.id]);

  if (!node) return null;

  const stepType = (node.data.type as string) ?? "action";

  const handleSave = () => {
    onUpdate(node.id, { ...node.data, label, config });
  };

  const configFields = getConfigFields(stepType);

  const inputStyle: React.CSSProperties = {
    width: "100%",
    padding: "8px 10px",
    borderRadius: 8,
    border: `1px solid ${COLORS.border}`,
    background: COLORS.bg,
    fontSize: 12,
    fontFamily: FONTS.mono,
    color: COLORS.text,
    outline: "none",
    boxSizing: "border-box",
  };

  const labelStyle: React.CSSProperties = {
    fontSize: 11,
    fontFamily: FONTS.mono,
    color: COLORS.textMuted,
    marginBottom: 4,
    textTransform: "uppercase",
    letterSpacing: 0.5,
  };

  return (
    <div
      style={{
        width: 280,
        background: COLORS.surface,
        borderLeft: `1px solid ${COLORS.border}`,
        padding: 16,
        display: "flex",
        flexDirection: "column",
        gap: 12,
        flexShrink: 0,
        overflowY: "auto",
      }}
    >
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <div style={{ fontSize: 13, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>
          노드 설정
        </div>
        <button
          onClick={onClose}
          style={{
            background: "none",
            border: "none",
            cursor: "pointer",
            fontSize: 16,
            color: COLORS.textDim,
            padding: 2,
          }}
        >
          ✕
        </button>
      </div>

      <div>
        <div style={labelStyle}>이름</div>
        <input value={label} onChange={(e) => setLabel(e.target.value)} style={inputStyle} />
      </div>

      <div>
        <div style={labelStyle}>타입</div>
        <div
          style={{
            padding: "8px 10px",
            borderRadius: 8,
            background: COLORS.bg,
            fontSize: 12,
            fontFamily: FONTS.mono,
            color: COLORS.textMuted,
          }}
        >
          {stepType}
        </div>
      </div>

      {configFields.map((field) => (
        <div key={field.key}>
          <div style={labelStyle}>{field.label}</div>
          {field.multiline ? (
            <textarea
              value={config[field.key] ?? ""}
              onChange={(e) => setConfig((c) => ({ ...c, [field.key]: e.target.value }))}
              rows={3}
              style={{ ...inputStyle, resize: "vertical" }}
              placeholder={field.placeholder}
            />
          ) : (
            <input
              value={config[field.key] ?? ""}
              onChange={(e) => setConfig((c) => ({ ...c, [field.key]: e.target.value }))}
              style={inputStyle}
              placeholder={field.placeholder}
            />
          )}
        </div>
      ))}

      <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
        <button
          onClick={handleSave}
          style={{
            flex: 1,
            padding: "8px 0",
            borderRadius: 8,
            border: "none",
            background: COLORS.accent,
            color: "#fff",
            fontSize: 12,
            fontFamily: FONTS.sans,
            fontWeight: 600,
            cursor: "pointer",
          }}
        >
          저장
        </button>
        <button
          onClick={() => onDelete(node.id)}
          style={{
            padding: "8px 14px",
            borderRadius: 8,
            border: `1px solid ${COLORS.danger}`,
            background: "transparent",
            color: COLORS.danger,
            fontSize: 12,
            fontFamily: FONTS.sans,
            fontWeight: 600,
            cursor: "pointer",
          }}
        >
          삭제
        </button>
      </div>
    </div>
  );
}

interface ConfigField {
  key: string;
  label: string;
  placeholder?: string;
  multiline?: boolean;
}

function getConfigFields(type: string): ConfigField[] {
  switch (type) {
    case "llm":
      return [
        { key: "model", label: "모델", placeholder: "예: gpt-4, claude-3-opus" },
        { key: "prompt", label: "프롬프트", placeholder: "시스템 프롬프트 입력", multiline: true },
        { key: "temperature", label: "Temperature", placeholder: "0.7" },
        { key: "response_schema", label: "응답 스키마 (JSON)", placeholder: '{"type":"object","properties":{...}}', multiline: true },
      ];
    case "tool":
      return [
        { key: "toolName", label: "도구 이름", placeholder: "예: calculator, web_search" },
        { key: "input", label: "입력 매핑", placeholder: "JSON 형식", multiline: true },
      ];
    case "condition":
      return [
        { key: "expression", label: "조건식", placeholder: "예: result.score > 0.8", multiline: true },
        { key: "trueLabel", label: "참 라벨", placeholder: "true" },
        { key: "falseLabel", label: "거짓 라벨", placeholder: "false" },
      ];
    case "parallel":
      return [
        { key: "maxConcurrency", label: "최대 동시 실행", placeholder: "3" },
      ];
    case "approval":
      return [
        { key: "approver", label: "승인자", placeholder: "예: admin, manager" },
        { key: "message", label: "승인 메시지", placeholder: "승인 요청 메시지", multiline: true },
      ];
    case "action":
      return [
        { key: "actionType", label: "액션 유형", placeholder: "예: write, notify" },
        { key: "target", label: "대상", placeholder: "예: slack, database" },
        { key: "payload", label: "페이로드", placeholder: "JSON 형식", multiline: true },
      ];
    default:
      return [];
  }
}
