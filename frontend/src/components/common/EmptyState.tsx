import { COLORS, FONTS } from "../../theme";

interface EmptyStateProps {
  icon?: string;
  title: string;
  description?: string;
  action?: React.ReactNode;
}

export const EmptyState = ({ icon = "📭", title, description, action }: EmptyStateProps) => (
  <div
    style={{
      display: "flex",
      flexDirection: "column",
      alignItems: "center",
      justifyContent: "center",
      padding: "60px 40px",
      textAlign: "center",
      gap: 12,
    }}
  >
    <div style={{ fontSize: 48, marginBottom: 4 }}>{icon}</div>
    <div
      style={{
        fontSize: 16,
        fontWeight: 600,
        fontFamily: FONTS.sans,
        color: COLORS.textMuted,
      }}
    >
      {title}
    </div>
    {description && (
      <div
        style={{
          fontSize: 12,
          fontFamily: FONTS.mono,
          color: COLORS.textDim,
          maxWidth: 360,
          lineHeight: 1.6,
        }}
      >
        {description}
      </div>
    )}
    {action && <div style={{ marginTop: 8 }}>{action}</div>}
  </div>
);
