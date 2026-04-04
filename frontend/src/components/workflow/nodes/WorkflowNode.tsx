import { memo } from "react";
import { Handle, Position, type NodeProps } from "@xyflow/react";

const TYPE_STYLES: Record<string, { color: string; icon: string }> = {
  llm: { color: "#2563eb", icon: "🤖" },
  tool: { color: "#7c3aed", icon: "🔧" },
  condition: { color: "#d97706", icon: "⑂" },
  parallel: { color: "#059669", icon: "⚡" },
  approval: { color: "#dc2626", icon: "✋" },
  action: { color: "#6366f1", icon: "▶" },
  LLM_CALL: { color: "#2563eb", icon: "🤖" },
  TOOL_CALL: { color: "#7c3aed", icon: "🔧" },
  TOOL_USE: { color: "#7c3aed", icon: "🔧" },
  tool_use: { color: "#7c3aed", icon: "🔧" },
  CONDITION: { color: "#d97706", icon: "⑂" },
  PARALLEL: { color: "#059669", icon: "⚡" },
  HUMAN_INPUT: { color: "#dc2626", icon: "✋" },
  ACTION: { color: "#6366f1", icon: "▶" },
};

const STATUS_COLORS: Record<string, string> = {
  waiting: "#9ca3af",
  running: "#2563eb",
  completed: "#059669",
  failed: "#dc2626",
};

function WorkflowNode({ data, selected }: NodeProps) {
  const stepType = (data.type as string) ?? "action";
  const style = TYPE_STYLES[stepType] ?? TYPE_STYLES.action;
  const status = data.status as string | undefined;
  const borderColor = status ? (STATUS_COLORS[status] ?? style.color) : style.color;

  return (
    <div
      className="bg-card rounded-xl cursor-grab transition-shadow"
      style={{
        border: `2px solid ${borderColor}`,
        padding: "8px 12px",
        minWidth: 140,
        boxShadow: selected
          ? `0 0 0 2px ${style.color}40`
          : "0 1px 4px rgba(0,0,0,0.08)",
      }}
    >
      <Handle type="target" position={Position.Top} style={{ background: borderColor, width: 8, height: 8 }} />

      <div className="flex items-center gap-2">
        <span
          className="w-6 h-6 rounded-md flex items-center justify-center text-xs shrink-0"
          style={{ background: style.color + "18" }}
        >
          {style.icon}
        </span>
        <div className="flex-1 min-w-0">
          <div className="text-[11px] font-semibold text-foreground overflow-hidden text-ellipsis whitespace-nowrap">
            {(data.label as string) ?? "Untitled"}
          </div>
          <div
            className="text-[9px] font-mono uppercase tracking-wider"
            style={{ color: style.color }}
          >
            {stepType}
            {status && (
              <span className="ml-1.5" style={{ color: STATUS_COLORS[status] ?? "#9ca3af" }}>
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
