import { useState } from "react";
import { COLORS, FONTS } from "../../theme";

type ButtonVariant = "primary" | "danger" | "success" | "default" | "ghost";

const styles: Record<ButtonVariant, { bg: string; text: string; hover: string; border: string }> = {
  primary: { bg: COLORS.surface, text: COLORS.accent, hover: COLORS.accentDim, border: COLORS.accent },
  danger: { bg: COLORS.dangerDim, text: COLORS.danger, hover: "#fecaca", border: COLORS.danger },
  success: { bg: COLORS.successDim, text: COLORS.success, hover: "#a7f3d0", border: COLORS.success },
  default: { bg: COLORS.surfaceActive, text: COLORS.text, hover: COLORS.borderLight, border: COLORS.border },
  ghost: { bg: "transparent", text: COLORS.textMuted, hover: COLORS.surfaceHover, border: COLORS.border },
};

interface ActionButtonProps {
  children?: React.ReactNode;
  variant?: ButtonVariant;
  onClick?: () => void;
  icon?: React.ReactNode;
  small?: boolean;
  disabled?: boolean;
  type?: "button" | "submit";
}

export const ActionButton = ({
  children,
  variant = "default",
  onClick,
  icon,
  small,
  disabled,
  type = "button",
}: ActionButtonProps) => {
  const [hover, setHover] = useState(false);
  const s = styles[variant];

  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        display: "inline-flex",
        alignItems: "center",
        gap: 6,
        padding: small ? "5px 12px" : "8px 16px",
        borderRadius: 8,
        border: `1px solid ${s.border}`,
        background: hover && !disabled ? s.hover : s.bg,
        color: s.text,
        fontSize: small ? 12 : 13,
        fontWeight: 600,
        fontFamily: FONTS.sans,
        cursor: disabled ? "not-allowed" : "pointer",
        transition: "all 0.15s ease",
        opacity: disabled ? 0.5 : 1,
        whiteSpace: "nowrap",
      }}
    >
      {icon && <span style={{ fontSize: small ? 13 : 15 }}>{icon}</span>}
      {children}
    </button>
  );
};
