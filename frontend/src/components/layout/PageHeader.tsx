import { COLORS, FONTS } from "../../theme";

interface PageHeaderProps {
  title: string;
  subtitle?: string;
  actions?: React.ReactNode;
}

export const PageHeader = ({ title, subtitle, actions }: PageHeaderProps) => (
  <div
    style={{
      display: "flex",
      justifyContent: "space-between",
      alignItems: "center",
      marginBottom: 28,
    }}
  >
    <div>
      <h1
        style={{
          fontSize: 22,
          fontWeight: 700,
          fontFamily: FONTS.display,
          color: COLORS.text,
          margin: 0,
        }}
      >
        {title}
      </h1>
      {subtitle && (
        <p
          style={{
            fontSize: 12,
            color: COLORS.textMuted,
            fontFamily: FONTS.mono,
            margin: "4px 0 0",
          }}
        >
          {subtitle}
        </p>
      )}
    </div>
    {actions && <div style={{ display: "flex", gap: 8 }}>{actions}</div>}
  </div>
);
