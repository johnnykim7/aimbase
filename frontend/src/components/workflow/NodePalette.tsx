const PALETTE_ITEMS = [
  { type: "llm", label: "LLM 호출", icon: "🤖", color: "#2563eb" },
  { type: "tool", label: "도구 실행", icon: "🔧", color: "#7c3aed" },
  { type: "condition", label: "조건 분기", icon: "⑂", color: "#d97706" },
  { type: "parallel", label: "병렬 실행", icon: "⚡", color: "#059669" },
  { type: "approval", label: "승인 게이트", icon: "✋", color: "#dc2626" },
  { type: "action", label: "액션", icon: "▶", color: "#6366f1" },
  { type: "agent", label: "에이전트", icon: "🧬", color: "#0891b2" },
] as const;

export function NodePalette() {
  const onDragStart = (e: React.DragEvent, type: string, label: string) => {
    e.dataTransfer.setData("application/workflow-node-type", type);
    e.dataTransfer.setData("application/workflow-node-label", label);
    e.dataTransfer.effectAllowed = "move";
  };

  return (
    <div className="w-[200px] bg-card border-r border-border p-4 flex flex-col gap-2 shrink-0 overflow-y-auto">
      <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-wider mb-1">
        노드 팔레트
      </div>

      {PALETTE_ITEMS.map((item) => (
        <div
          key={item.type}
          draggable
          onDragStart={(e) => onDragStart(e, item.type, item.label)}
          className="cursor-grab flex items-center gap-2 transition-colors rounded-xl"
          style={{
            padding: "10px 12px",
            border: `1px solid ${item.color}40`,
            background: item.color + "0a",
          }}
          onMouseEnter={(e) => {
            e.currentTarget.style.background = item.color + "18";
            e.currentTarget.style.borderColor = item.color;
          }}
          onMouseLeave={(e) => {
            e.currentTarget.style.background = item.color + "0a";
            e.currentTarget.style.borderColor = item.color + "40";
          }}
        >
          <span
            className="w-7 h-7 rounded-lg flex items-center justify-center text-sm shrink-0"
            style={{ background: item.color + "18" }}
          >
            {item.icon}
          </span>
          <div>
            <div className="text-xs font-semibold text-foreground">
              {item.label}
            </div>
            <div className="text-[10px] font-mono uppercase" style={{ color: item.color }}>
              {item.type}
            </div>
          </div>
        </div>
      ))}

      <div className="mt-auto pt-2.5 border-t border-border text-[11px] font-mono text-muted-foreground/40 leading-normal">
        팔레트에서 노드를 캔버스로 드래그하세요
      </div>
    </div>
  );
}
