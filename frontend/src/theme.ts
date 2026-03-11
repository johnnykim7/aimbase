export const COLORS = {
  bg: "#f0f2f5",
  surface: "#ffffff",
  surfaceHover: "#f5f7fa",
  surfaceActive: "#edf0f5",
  border: "#dde1ea",
  borderLight: "#c8cdd8",
  text: "#1a1d27",
  textMuted: "#5a6072",
  textDim: "#9198aa",
  accent: "#2563eb",
  accentDim: "#dbeafe",
  success: "#059669",
  successDim: "#d1fae5",
  warning: "#d97706",
  warningDim: "#fef3c7",
  danger: "#dc2626",
  dangerDim: "#fee2e2",
  purple: "#7c3aed",
  purpleDim: "#ede9fe",
} as const;

export const FONTS = {
  mono: "'JetBrains Mono', 'Fira Code', monospace",
  sans: "'DM Sans', 'Pretendard', sans-serif",
  display: "'Space Grotesk', 'Pretendard', sans-serif",
} as const;

export type BadgeColor = "accent" | "success" | "warning" | "danger" | "purple" | "muted";
