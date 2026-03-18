import { COLORS, FONTS } from "../../theme";

const PALETTE_ITEMS = [
  { type: "llm", label: "LLM 호출", icon: "🤖", color: COLORS.accent },
  { type: "tool", label: "도구 실행", icon: "🔧", color: COLORS.purple },
  { type: "condition", label: "조건 분기", icon: "⑂", color: COLORS.warning },
  { type: "parallel", label: "병렬 실행", icon: "⚡", color: COLORS.success },
  { type: "approval", label: "승인 게이트", icon: "✋", color: COLORS.danger },
  { type: "action", label: "액션", icon: "▶", color: "#6366f1" },
] as const;

export function NodePalette() {
  const onDragStart = (e: React.DragEvent, type: string, label: string) => {
    e.dataTransfer.setData("application/workflow-node-type", type);
    e.dataTransfer.setData("application/workflow-node-label", label);
    e.dataTransfer.effectAllowed = "move";
  };

  return (
    <div
      style={{
        width: 200,
        background: COLORS.surface,
        borderRight: `1px solid ${COLORS.border}`,
        padding: 16,
        display: "flex",
        flexDirection: "column",
        gap: 8,
        flexShrink: 0,
        overflowY: "auto",
      }}
    >
      <div
        style={{
          fontSize: 11,
          fontFamily: FONTS.mono,
          color: COLORS.textMuted,
          textTransform: "uppercase",
          letterSpacing: 1,
          marginBottom: 4,
        }}
      >
        노드 팔레트
      </div>

      {PALETTE_ITEMS.map((item) => (
        <div
          key={item.type}
          draggable
          onDragStart={(e) => onDragStart(e, item.type, item.label)}
          style={{
            padding: "10px 12px",
            borderRadius: 10,
            border: `1px solid ${item.color}40`,
            background: item.color + "0a",
            cursor: "grab",
            display: "flex",
            alignItems: "center",
            gap: 8,
            transition: "background 0.15s, border-color 0.15s",
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
            style={{
              width: 28,
              height: 28,
              borderRadius: 8,
              background: item.color + "18",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              fontSize: 14,
              flexShrink: 0,
            }}
          >
            {item.icon}
          </span>
          <div>
            <div style={{ fontSize: 12, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>
              {item.label}
            </div>
            <div style={{ fontSize: 10, fontFamily: FONTS.mono, color: item.color, textTransform: "uppercase" }}>
              {item.type}
            </div>
          </div>
        </div>
      ))}

      <div
        style={{
          marginTop: "auto",
          padding: "10px 0",
          borderTop: `1px solid ${COLORS.border}`,
          fontSize: 11,
          fontFamily: FONTS.mono,
          color: COLORS.textDim,
          lineHeight: 1.5,
        }}
      >
        팔레트에서 노드를 캔버스로 드래그하세요
      </div>
    </div>
  );
}
