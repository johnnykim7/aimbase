import { COLORS, FONTS } from "../../theme";
import { ActionButton } from "../common/ActionButton";

interface StudioToolbarProps {
  name: string;
  onNameChange: (name: string) => void;
  onSave: () => void;
  onRun: () => void;
  onAutoLayout: () => void;
  onBack: () => void;
  onToggleSchema?: () => void;
  schemaActive?: boolean;
  saving?: boolean;
  running?: boolean;
  dirty?: boolean;
}

export function StudioToolbar({
  name,
  onNameChange,
  onSave,
  onRun,
  onAutoLayout,
  onBack,
  onToggleSchema,
  schemaActive,
  saving,
  running,
  dirty,
}: StudioToolbarProps) {
  return (
    <div
      style={{
        height: 52,
        background: COLORS.surface,
        borderBottom: `1px solid ${COLORS.border}`,
        display: "flex",
        alignItems: "center",
        padding: "0 16px",
        gap: 12,
        flexShrink: 0,
      }}
    >
      <button
        onClick={onBack}
        style={{
          background: "none",
          border: "none",
          cursor: "pointer",
          fontSize: 18,
          color: COLORS.textMuted,
          padding: "4px 8px",
          borderRadius: 6,
        }}
      >
        ←
      </button>

      <input
        value={name}
        onChange={(e) => onNameChange(e.target.value)}
        style={{
          fontSize: 15,
          fontFamily: FONTS.sans,
          fontWeight: 600,
          color: COLORS.text,
          border: "none",
          background: "transparent",
          outline: "none",
          width: 240,
        }}
        placeholder="워크플로우 이름"
      />

      {dirty && (
        <span style={{ fontSize: 10, fontFamily: FONTS.mono, color: COLORS.warning }}>
          변경됨
        </span>
      )}

      <div style={{ flex: 1 }} />

      <ActionButton small variant="ghost" onClick={onAutoLayout}>
        정렬
      </ActionButton>
      {onToggleSchema && (
        <ActionButton
          small
          variant={schemaActive ? "primary" : "ghost"}
          onClick={onToggleSchema}
        >
          출력 스키마
        </ActionButton>
      )}
      <ActionButton small variant="default" onClick={onRun} disabled={running}>
        {running ? "실행 중..." : "실행"}
      </ActionButton>
      <ActionButton small variant="primary" onClick={onSave} disabled={saving}>
        {saving ? "저장 중..." : "저장"}
      </ActionButton>
    </div>
  );
}
