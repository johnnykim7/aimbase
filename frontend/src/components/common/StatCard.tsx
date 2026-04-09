interface StatCardProps {
  label: string;
  value: string | number;
  sub?: string;
  color?: string;
}

export const StatCard = ({ label, value, sub, color = "hsl(var(--primary))" }: StatCardProps) => (
  <div
    className="bg-card border border-border rounded-xl px-5 py-4 flex-1 min-w-[160px] shadow-xs shadow-black/5"
    style={{ borderTop: `2.5px solid ${color}` }}
  >
    <div className="text-xs text-muted-foreground font-medium mb-2">
      {label}
    </div>
    <div className="text-2xl font-bold text-foreground leading-none">
      {value}
    </div>
    {sub && (
      <div className="text-[11px] text-muted-foreground/60 mt-1.5">{sub}</div>
    )}
  </div>
);
