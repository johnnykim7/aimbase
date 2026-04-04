import { COLORS, FONTS } from "../../theme";
import { usePlatformWorkflows } from "../../hooks/usePlatformWorkflows";

const PALETTE_ITEMS = [
  { type: "llm", label: "LLM 호출", icon: "🤖", color: COLORS.accent },
  { type: "tool", label: "도구 실행", icon: "🔧", color: COLORS.purple },
  { type: "condition", label: "조건 분기", icon: "⑂", color: COLORS.warning },
  { type: "parallel", label: "병렬 실행", icon: "⚡", color: COLORS.success },
  { type: "approval", label: "승인 게이트", icon: "✋", color: COLORS.danger },
  { type: "action", label: "액션", icon: "▶", color: "#6366f1" },
] as const;

const SUB_WORKFLOW_COLOR = "#0891b2";

export function NodePalette() {
  const { data: platformWorkflows } = usePlatformWorkflows();

  const onDragStart = (
    e: React.DragEvent,
    type: string,
    label: string,
    extra?: Record<string, string>
  ) => {
    e.dataTransfer.setData("application/workflow-node-type", type);
    e.dataTransfer.setData("application/workflow-node-label", label);
    if (extra) {
      Object.entries(extra).forEach(([k, v]) =>
        e.dataTransfer.setData(k, v)
      );
    }
    e.dataTransfer.effectAllowed = "move";
  };

  const renderItem = (
    key: string,
    type: string,
    label: string,
    icon: string,
    color: string,
    subtitle?: string,
    extra?: Record<string, string>
  ) => (
    <div
      key={key}
      draggable
      onDragStart={(e) => onDragStart(e, type, label, extra)}
      style={{
        padding: "10px 12px",
        borderRadius: 10,
        border: `1px solid ${color}40`,
        background: color + "0a",
        cursor: "grab",
        display: "flex",
        alignItems: "center",
        gap: 8,
        transition: "background 0.15s, border-color 0.15s",
      }}
      onMouseEnter={(e) => {
        e.currentTarget.style.background = color + "18";
        e.currentTarget.style.borderColor = color;
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.background = color + "0a";
        e.currentTarget.style.borderColor = color + "40";
      }}
    >
      <span
        style={{
          width: 28,
          height: 28,
          borderRadius: 8,
          background: color + "18",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          fontSize: 14,
          flexShrink: 0,
        }}
      >
        {icon}
      </span>
      <div style={{ minWidth: 0, flex: 1 }}>
        <div
          style={{
            fontSize: 12,
            fontWeight: 600,
            fontFamily: FONTS.sans,
            color: COLORS.text,
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
        >
          {label}
        </div>
        <div
          style={{
            fontSize: 10,
            fontFamily: FONTS.mono,
            color: color,
            textTransform: "uppercase",
          }}
        >
          {subtitle ?? type}
        </div>
      </div>
    </div>
  );

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

      {PALETTE_ITEMS.map((item) =>
        renderItem(item.type, item.type, item.label, item.icon, item.color)
      )}

      {platformWorkflows && platformWorkflows.length > 0 && (
        <>
          <div
            style={{
              fontSize: 11,
              fontFamily: FONTS.mono,
              color: COLORS.textMuted,
              textTransform: "uppercase",
              letterSpacing: 1,
              marginTop: 12,
              marginBottom: 4,
              paddingTop: 12,
              borderTop: `1px solid ${COLORS.border}`,
            }}
          >
            공용 워크플로우
          </div>

          {platformWorkflows.map((pw) =>
            renderItem(
              `pw-${pw.id}`,
              "sub_workflow",
              pw.name,
              "📦",
              SUB_WORKFLOW_COLOR,
              pw.category ?? "sub_workflow",
              { "application/workflow-sub-id": pw.id }
            )
          )}
        </>
      )}

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
