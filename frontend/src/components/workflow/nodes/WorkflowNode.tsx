import { memo } from "react";
import { Handle, Position, type NodeProps } from "@xyflow/react";
import { COLORS, FONTS } from "../../../theme";

const TYPE_STYLES: Record<string, { color: string; icon: string }> = {
  llm: { color: COLORS.accent, icon: "🤖" },
  tool: { color: COLORS.purple, icon: "🔧" },
  condition: { color: COLORS.warning, icon: "⑂" },
  parallel: { color: COLORS.success, icon: "⚡" },
  approval: { color: COLORS.danger, icon: "✋" },
  action: { color: "#6366f1", icon: "▶" },
};

const STATUS_COLORS: Record<string, string> = {
  waiting: COLORS.textDim,
  running: COLORS.accent,
  completed: COLORS.success,
  failed: COLORS.danger,
};

function WorkflowNode({ data, selected }: NodeProps) {
  const stepType = (data.type as string) ?? "action";
  const style = TYPE_STYLES[stepType] ?? TYPE_STYLES.action;
  const status = data.status as string | undefined;
  const borderColor = status ? (STATUS_COLORS[status] ?? style.color) : style.color;

  return (
    <div
      style={{
        background: COLORS.surface,
        border: `2px solid ${borderColor}`,
        borderRadius: 12,
        padding: "10px 16px",
        minWidth: 180,
        boxShadow: selected
          ? `0 0 0 2px ${style.color}40`
          : "0 1px 4px rgba(0,0,0,0.08)",
        cursor: "grab",
        transition: "box-shadow 0.15s, border-color 0.15s",
      }}
    >
      <Handle type="target" position={Position.Top} style={{ background: borderColor, width: 8, height: 8 }} />

      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <span
          style={{
            width: 28,
            height: 28,
            borderRadius: 8,
            background: style.color + "18",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            fontSize: 14,
            flexShrink: 0,
          }}
        >
          {style.icon}
        </span>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div
            style={{
              fontSize: 12,
              fontFamily: FONTS.sans,
              fontWeight: 600,
              color: COLORS.text,
              overflow: "hidden",
              textOverflow: "ellipsis",
              whiteSpace: "nowrap",
            }}
          >
            {(data.label as string) ?? "Untitled"}
          </div>
          <div
            style={{
              fontSize: 10,
              fontFamily: FONTS.mono,
              color: style.color,
              textTransform: "uppercase",
              letterSpacing: 0.5,
            }}
          >
            {stepType}
            {status && (
              <span style={{ marginLeft: 6, color: STATUS_COLORS[status] ?? COLORS.textDim }}>
                • {status}
              </span>
            )}
          </div>
        </div>
      </div>

      <Handle type="source" position={Position.Bottom} style={{ background: borderColor, width: 8, height: 8 }} />
    </div>
  );
}

export default memo(WorkflowNode);
