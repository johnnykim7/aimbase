import { COLORS, FONTS, type BadgeColor } from "../../theme";

const colorMap: Record<BadgeColor, { bg: string; text: string; dot: string }> = {
  accent: { bg: COLORS.accentDim + "60", text: COLORS.accent, dot: COLORS.accent },
  success: { bg: COLORS.successDim + "60", text: COLORS.success, dot: COLORS.success },
  warning: { bg: COLORS.warningDim + "60", text: COLORS.warning, dot: COLORS.warning },
  danger: { bg: COLORS.dangerDim + "60", text: COLORS.danger, dot: COLORS.danger },
  purple: { bg: COLORS.purpleDim + "60", text: COLORS.purple, dot: COLORS.purple },
  muted: { bg: COLORS.border, text: COLORS.textMuted, dot: COLORS.textDim },
};

interface BadgeProps {
  color?: BadgeColor;
  pulse?: boolean;
  children: React.ReactNode;
}

export const Badge = ({ color = "accent", pulse, children }: BadgeProps) => {
  const c = colorMap[color];
  return (
    <span
      style={{
        display: "inline-flex",
        alignItems: "center",
        gap: 6,
        padding: "3px 10px",
        borderRadius: 6,
        fontSize: 11,
        fontWeight: 600,
        fontFamily: FONTS.mono,
        background: c.bg,
        color: c.text,
        letterSpacing: 0.3,
        whiteSpace: "nowrap",
      }}
    >
      {pulse && (
        <span
          style={{
            width: 6,
            height: 6,
            borderRadius: "50%",
            background: c.dot,
            animation: "pulse 2s ease-in-out infinite",
            flexShrink: 0,
          }}
        />
      )}
      {children}
    </span>
  );
};
