interface FormFieldProps {
  label: string;
  children: React.ReactNode;
  hint?: string;
}

export const FormField = ({ label, children, hint }: FormFieldProps) => (
  <div className="mb-4">
    <label className="block text-xs font-medium text-foreground mb-1.5">
      {label}
    </label>
    {children}
    {hint && (
      <div className="text-[11px] text-muted-foreground/60 mt-1">{hint}</div>
    )}
  </div>
);

/** @deprecated Use Tailwind classes directly */
export const inputStyle: React.CSSProperties = {
  width: "100%",
  padding: "9px 12px",
  borderRadius: 8,
  border: "1px solid hsl(var(--border))",
  background: "hsl(var(--card))",
  color: "hsl(var(--foreground))",
  fontSize: 13,
  fontFamily: "var(--font-mono, monospace)",
  boxSizing: "border-box",
  transition: "border-color 0.15s",
};

/** @deprecated Use Tailwind classes directly */
export const selectStyle: React.CSSProperties = {
  ...inputStyle,
  cursor: "pointer",
};

/** @deprecated Use Tailwind classes directly */
export const textareaStyle: React.CSSProperties = {
  ...inputStyle,
  resize: "vertical",
  minHeight: 100,
};
