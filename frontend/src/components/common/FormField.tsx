import { COLORS, FONTS } from "../../theme";

interface FormFieldProps {
  label: string;
  children: React.ReactNode;
  hint?: string;
}

export const FormField = ({ label, children, hint }: FormFieldProps) => (
  <div style={{ marginBottom: 16 }}>
    <label
      style={{
        display: "block",
        fontSize: 11,
        fontFamily: FONTS.mono,
        color: COLORS.textMuted,
        textTransform: "uppercase",
        letterSpacing: 1,
        marginBottom: 6,
      }}
    >
      {label}
    </label>
    {children}
    {hint && (
      <div style={{ fontSize: 11, color: COLORS.textDim, marginTop: 4, fontFamily: FONTS.mono }}>
        {hint}
      </div>
    )}
  </div>
);

export const inputStyle: React.CSSProperties = {
  width: "100%",
  padding: "10px 14px",
  borderRadius: 8,
  border: `1px solid ${COLORS.border}`,
  background: COLORS.surfaceActive,
  color: COLORS.text,
  fontSize: 13,
  fontFamily: FONTS.mono,
  boxSizing: "border-box",
  transition: "border-color 0.15s",
};

export const selectStyle: React.CSSProperties = {
  ...inputStyle,
  cursor: "pointer",
};

export const textareaStyle: React.CSSProperties = {
  ...inputStyle,
  resize: "vertical",
  minHeight: 100,
};
