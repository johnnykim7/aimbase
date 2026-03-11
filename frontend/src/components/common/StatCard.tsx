import { COLORS, FONTS } from "../../theme";

interface StatCardProps {
  label: string;
  value: string | number;
  sub?: string;
  color?: string;
}

export const StatCard = ({ label, value, sub, color = COLORS.accent }: StatCardProps) => (
  <div
    style={{
      background: COLORS.surface,
      border: `1px solid ${COLORS.border}`,
      borderRadius: 12,
      padding: "20px 24px",
      flex: 1,
      minWidth: 160,
      borderTop: `2px solid ${color}`,
    }}
  >
    <div
      style={{
        fontSize: 11,
        color: COLORS.textMuted,
        fontFamily: FONTS.mono,
        textTransform: "uppercase",
        letterSpacing: 1.2,
        marginBottom: 8,
      }}
    >
      {label}
    </div>
    <div
      style={{
        fontSize: 32,
        fontWeight: 700,
        fontFamily: FONTS.display,
        color: COLORS.text,
        lineHeight: 1,
      }}
    >
      {value}
    </div>
    {sub && (
      <div style={{ fontSize: 12, color: COLORS.textMuted, marginTop: 6, fontFamily: FONTS.mono }}>
        {sub}
      </div>
    )}
  </div>
);
